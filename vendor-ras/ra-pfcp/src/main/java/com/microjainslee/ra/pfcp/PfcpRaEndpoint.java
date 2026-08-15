package com.microjainslee.ra.pfcp;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.command.PfcpOutboundCommand;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import com.microjainslee.ra.pfcp.command.SendPfcpMessage;
import com.microjainslee.ra.pfcp.event.PfcpAssociationEvent;
import com.microjainslee.ra.pfcp.event.PfcpHeartbeatEvent;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JAINSLEE 3-port PFCP RA. Association activity = Sxa/Sxb peer.
 * Session activity = SEID. Live = Association Setup Response or Heartbeat Response.
 */
public final class PfcpRaEndpoint implements RaEndpointPort, RaCommandPort {

    public static final String RA_NAME = "pfcp-ra";
    private static final Logger LOG = LogManager.getLogger(PfcpRaEndpoint.class);

    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<String, PeerState> peers = new ConcurrentHashMap<>();
    private final Map<Long, PfcpProgramSession> sessions = new ConcurrentHashMap<>();
    private volatile RaBootstrapPort bootstrap;
    private volatile boolean listening;
    private volatile OutboundSink sink = OutboundSink.NOOP;
    private volatile DatagramSocket socket;

    public void setSink(OutboundSink sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    @Override
    public String getRaName() {
        return RA_NAME;
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap);
        this.listening = true;
        LOG.info("pfcp-ra ACTIVE localListen=true live=false");
    }

    /** Optional UDP bind. Tests inject {@link #receive}; runtime binds :8805. */
    public void bindUdp(InetSocketAddress local) {
        Objects.requireNonNull(local);
        try {
            DatagramSocket s = new DatagramSocket(local);
            this.socket = s;
            Thread.ofVirtual().name("pfcp-udp-" + local.getPort()).start(() -> loop(s));
            LOG.info("pfcp-ra UDP bound {} live=false", local);
        } catch (Exception e) {
            LOG.warn("pfcp-ra UDP bind failed {}: {}", local, e.toString());
        }
    }

    @Override
    public void deactivate() {
        listening = false;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            s.close();
        }
        peers.clear();
        sessions.clear();
        LOG.info("pfcp-ra INACTIVE");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (!(command instanceof PfcpOutboundCommand pfcp)) {
            LOG.warn("pfcp-ra unknown command {}", command == null ? "null" : command.getClass());
            return;
        }
        switch (pfcp) {
            case PfcpAssociateCommand assoc -> {
                int s = seq.getAndIncrement() & 0xff_ffff;
                emit(PfcpCodec.encode(PfcpMessage.associationSetupRequest(s)), assoc.upf());
            }
            case PfcpProgramSession program -> {
                sessions.put(program.seid(), program);
                int s = seq.getAndIncrement() & 0xff_ffff;
                emit(PfcpCodec.encode(new PfcpMessage(
                        PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, program.seid(), s,
                        new byte[] {(byte) program.rules().size()})), program.upf());
            }
            case SendPfcpMessage send -> emit(PfcpCodec.encode(send.message()), send.peer());
        }
    }

    public void receive(byte[] wire, InetSocketAddress peer) {
        PfcpMessage msg = PfcpCodec.decode(wire);
        switch (msg.type()) {
            case HEARTBEAT_REQUEST -> emit(
                    PfcpCodec.encode(PfcpMessage.heartbeatResponse(msg.sequence())), peer);
            case HEARTBEAT_RESPONSE -> {
                peers.put(peer.toString(), PeerState.up("pfcp:" + peer, "heartbeat-response"));
                fire(new PfcpHeartbeatEvent(peer, true), "pfcp-link");
            }
            case ASSOCIATION_SETUP_REQUEST -> emit(
                    PfcpCodec.encode(PfcpMessage.associationSetupResponse(msg.sequence())), peer);
            case ASSOCIATION_SETUP_RESPONSE -> {
                peers.put(peer.toString(), PeerState.up("pfcp:" + peer, "association-setup-response"));
                fire(new PfcpAssociationEvent(peer, true), "pfcp-link");
            }
            default -> fire(new PfcpMessageEvent(msg, peer), "pfcp-se-" + Long.toUnsignedString(msg.seid()));
        }
    }

    public PeerState peer(InetSocketAddress peer) {
        return peers.getOrDefault(peer.toString(),
                PeerState.down("pfcp:" + peer, listening, "no-association"));
    }

    public boolean listening() {
        return listening;
    }

    public boolean sessionInstalled(long seid) {
        return sessions.containsKey(seid);
    }

    private void emit(byte[] bytes, InetSocketAddress peer) {
        sink.send(bytes, peer);
        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) {
            try {
                s.send(new DatagramPacket(bytes, bytes.length, peer));
            } catch (Exception e) {
                LOG.debug("pfcp-ra send {}", e.toString());
            }
        }
    }

    private void loop(DatagramSocket s) {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (listening && !s.isClosed()) {
            try {
                packet.setData(buf);
                s.receive(packet);
                byte[] wire = Arrays.copyOf(packet.getData(), packet.getLength());
                receive(wire, (InetSocketAddress) packet.getSocketAddress());
            } catch (Exception e) {
                if (!listening || s.isClosed()) {
                    return;
                }
                LOG.debug("pfcp-ra rx {}", e.toString());
            }
        }
    }

    private void fire(SleeEvent event, String activityId) {
        RaBootstrapPort b = bootstrap;
        if (b == null) {
            return;
        }
        ActivityHandle h = b.createActivityHandle(activityId);
        b.fireEvent(event, h, (Address) () -> activityId);
    }

    @FunctionalInterface
    public interface OutboundSink {
        OutboundSink NOOP = (bytes, peer) -> { };

        void send(byte[] bytes, InetSocketAddress peer);
    }
}
