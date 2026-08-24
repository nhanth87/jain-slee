package com.microjainslee.ra.pfcp;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.command.PfcpHeartbeatCommand;
import com.microjainslee.ra.pfcp.command.PfcpOutboundCommand;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import com.microjainslee.ra.pfcp.command.PfcpSessionDeletion;
import com.microjainslee.ra.pfcp.command.PfcpSessionEstablishment;
import com.microjainslee.ra.pfcp.command.PfcpSessionModification;
import com.microjainslee.ra.pfcp.command.PfcpSessionReport;
import com.microjainslee.ra.pfcp.command.SendPfcpMessage;
import com.microjainslee.ra.pfcp.event.PfcpAssociationEvent;
import com.microjainslee.ra.pfcp.event.PfcpHeartbeatEvent;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JAINSLEE 3-port PFCP RA. Association activity = Sxa/Sxb peer. Session
 * activity = SEID. Peer-ready = Association Setup Response; liveness is then
 * maintained by the Heartbeat driver; a heartbeat timeout or a peer recovery
 * timestamp change re-associates.
 *
 * <p>Never uses {@code new Disruptor}. Inbound events fire via
 * {@link RaBootstrapPort#fireEvent}.</p>
 */
public final class PfcpRaEndpoint implements RaEndpointPort, RaCommandPort {

    public static final String RA_NAME = "pfcp-ra";
    private static final Logger LOG = LogManager.getLogger(PfcpRaEndpoint.class);

    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<String, PfcpPeer> peers = new ConcurrentHashMap<>();
    private final Map<Long, PfcpProgramSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, InetSocketAddress> pendingAssoc = new ConcurrentHashMap<>();
    private volatile RaBootstrapPort bootstrap;
    private volatile boolean listening;
    private volatile OutboundSink sink = OutboundSink.NOOP;
    private volatile DatagramSocket socket;

    private volatile PfcpNodeId nodeId = PfcpNodeId.of(InetAddress.getLoopbackAddress());
    private volatile int recoverySeconds = (int) (System.currentTimeMillis() / 1000L);
    private volatile long heartbeatIntervalMs = PfcpHeartbeatDriver.DEFAULT_INTERVAL_MS;
    private volatile long heartbeatTimeoutMs = PfcpHeartbeatDriver.DEFAULT_TIMEOUT_MS;
    private volatile PfcpHeartbeatDriver heartbeats;

    public void setSink(OutboundSink sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    /** CP node id advertised in Association/Heartbeat. Default: loopback. */
    public void setNodeId(PfcpNodeId nodeId) {
        this.nodeId = Objects.requireNonNull(nodeId);
    }

    /** Control-plane Recovery Time Stamp (seconds). */
    public void setRecoverySeconds(int recoverySeconds) {
        this.recoverySeconds = recoverySeconds;
    }

    /** Configure the heartbeat loop before {@link #activate(RaBootstrapPort)}. */
    public void setHeartbeat(long intervalMs, long timeoutMs) {
        if (intervalMs <= 0 || timeoutMs < intervalMs) {
            throw new IllegalArgumentException("heartbeat interval > 0 and timeout >= interval");
        }
        this.heartbeatIntervalMs = intervalMs;
        this.heartbeatTimeoutMs = timeoutMs;
    }

    public PfcpNodeId nodeId() {
        return nodeId;
    }

    public int recoverySeconds() {
        return recoverySeconds;
    }

    @Override
    public String getRaName() {
        return RA_NAME;
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap);
        this.listening = true;
        this.heartbeats = new PfcpHeartbeatDriver(heartbeatIntervalMs, heartbeatTimeoutMs,
                new PfcpHeartbeatDriver.Handler() {
                    @Override
                    public void sendHeartbeat(InetSocketAddress peer) {
                        doSendHeartbeat(peer);
                    }

                    @Override
                    public void heartbeatTimedOut(InetSocketAddress peer) {
                        PfcpRaEndpoint.this.heartbeatTimeout(peer);
                    }
                });
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
        PfcpHeartbeatDriver hb = heartbeats;
        heartbeats = null;
        if (hb != null) {
            hb.shutdown();
        }
        peers.clear();
        sessions.clear();
        pendingAssoc.clear();
        LOG.info("pfcp-ra INACTIVE");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (!(command instanceof PfcpOutboundCommand pfcp)) {
            LOG.warn("pfcp-ra unknown command {}", command == null ? "null" : command.getClass());
            return;
        }
        switch (pfcp) {
            case PfcpAssociateCommand assoc -> associate(assoc.upf());
            case PfcpHeartbeatCommand hb -> doSendHeartbeat(hb.upf());
            case PfcpSessionEstablishment est -> sendSession(
                    PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, est.upf(), est.seid(), est.ies());
            case PfcpSessionModification mod -> sendSession(
                    PfcpMessageType.SESSION_MODIFICATION_REQUEST, mod.upf(), mod.seid(), mod.ies());
            case PfcpSessionDeletion del -> sendSession(
                    PfcpMessageType.SESSION_DELETION_REQUEST, del.upf(), del.seid(), del.ies());
            case PfcpSessionReport rep -> sendSession(
                    PfcpMessageType.SESSION_REPORT_REQUEST, rep.upf(), rep.seid(), rep.ies());
            case PfcpProgramSession program -> {
                // Backward-compatible alias (legacy PfcpRule bundle).
                sessions.put(program.seid(), program);
                int s = nextSequence();
                emit(PfcpCodec.encode(new PfcpMessage(
                        PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, program.seid(), s,
                        PfcpCodec.encodeRules(program.rules(), program.ueIpv4()))), program.upf());
            }
            case SendPfcpMessage send -> emit(PfcpCodec.encode(send.message()), send.peer());
        }
    }

    public void receive(byte[] wire, InetSocketAddress peer) {
        PfcpMessage msg;
        try {
            msg = PfcpCodec.decode(wire);
        } catch (IllegalArgumentException e) {
            LOG.debug("pfcp-ra decode {}", e.toString());
            return;
        }
        if (msg.type() == PfcpMessageType.UNKNOWN
                || msg.type() == PfcpMessageType.VERSION_NOT_SUPPORTED_RESPONSE) {
            LOG.debug("pfcp-ra ignore {}", msg.type());
            return;
        }
        switch (msg.type()) {
            case HEARTBEAT_REQUEST -> emit(PfcpCodec.encode(
                    PfcpMessage.heartbeatResponse(msg.sequence(), recoverySeconds)), peer);
            case HEARTBEAT_RESPONSE -> onHeartbeatResponse(peer, msg);
            case ASSOCIATION_SETUP_REQUEST -> emit(PfcpCodec.encode(
                    PfcpMessage.associationSetupResponse(msg.sequence(), nodeId,
                            recoverySeconds, PfcpIe.CAUSE_REQUEST_ACCEPTED)), peer);
            case ASSOCIATION_SETUP_RESPONSE -> onAssociationResponse(peer, msg);
            default -> fire(new PfcpMessageEvent(msg, peer),
                    "pfcp-se-" + Long.toUnsignedString(msg.seid()));
        }
    }

    public PeerState peer(InetSocketAddress peer) {
        PfcpPeer p = peers.get(peer.toString());
        if (p == null || !p.associated) {
            return PeerState.down("pfcp:" + peer, listening,
                    p == null ? "no-association" : p.evidence);
        }
        return PeerState.up("pfcp:" + peer, "association-setup");
    }

    public boolean listening() {
        return listening;
    }

    public boolean sessionInstalled(long seid) {
        return sessions.containsKey(seid);
    }

    /** Package-private recovery trigger: exposed for deterministic unit tests. */
    void heartbeatTimeout(InetSocketAddress peer) {
        PfcpPeer p = peers.computeIfAbsent(peer.toString(), k -> new PfcpPeer(peer));
        p.associated = false;
        p.evidence = "heartbeat-timeout";
        fire(new PfcpHeartbeatEvent(peer, false), "pfcp-link");
        reAssociate(peer);
    }

    void doSendHeartbeat(InetSocketAddress peer) {
        int s = nextSequence();
        emit(PfcpCodec.encode(PfcpMessage.heartbeatRequest(s, recoverySeconds)), peer);
    }

    private void associate(InetSocketAddress upf) {
        int s = nextSequence();
        pendingAssoc.put(s, upf);
        emit(PfcpCodec.encode(PfcpMessage.associationSetupRequest(s, nodeId, recoverySeconds)), upf);
    }

    private void reAssociate(InetSocketAddress upf) {
        PfcpHeartbeatDriver hb = heartbeats;
        if (hb != null) {
            hb.disassociate(upf);
        }
        associate(upf);
    }

    private void sendSession(PfcpMessageType type, InetSocketAddress upf, long seid, List<PfcpIe> ies) {
        int s = nextSequence();
        PfcpMessage msg = switch (type) {
            case SESSION_ESTABLISHMENT_REQUEST -> PfcpMessage.sessionEstablishmentRequest(seid, s, ies);
            case SESSION_MODIFICATION_REQUEST -> PfcpMessage.sessionModificationRequest(seid, s, ies);
            case SESSION_DELETION_REQUEST -> PfcpMessage.sessionDeletionRequest(seid, s, ies);
            case SESSION_REPORT_REQUEST -> PfcpMessage.sessionReportRequest(seid, s, ies);
            default -> throw new IllegalArgumentException("not a session request: " + type);
        };
        emit(PfcpCodec.encode(msg), upf);
    }

    private void onAssociationResponse(InetSocketAddress peer, PfcpMessage msg) {
        PfcpPeer p = peers.computeIfAbsent(peer.toString(), k -> new PfcpPeer(peer));
        p.associated = true;
        p.evidence = "association-setup";
        int recovery = msg.recoveryTimeStamp();
        if (recovery != -1) {
            p.peerRecovery = recovery;
        }
        pendingAssoc.entrySet().removeIf(e -> e.getValue().equals(peer));
        fire(new PfcpAssociationEvent(peer, true), "pfcp-link");
        PfcpHeartbeatDriver hb = heartbeats;
        if (hb != null) {
            hb.associate(peer);
        }
    }

    private void onHeartbeatResponse(InetSocketAddress peer, PfcpMessage msg) {
        PfcpHeartbeatDriver hb = heartbeats;
        if (hb != null) {
            hb.onResponse(peer);
        }
        PfcpPeer p = peers.computeIfAbsent(peer.toString(), k -> new PfcpPeer(peer));
        p.associated = true;
        p.evidence = "heartbeat-response";
        int recovery = msg.recoveryTimeStamp();
        if (recovery != -1) {
            if (p.peerRecovery != -1 && p.peerRecovery != recovery) {
                p.peerRecovery = recovery;
                fire(new PfcpHeartbeatEvent(peer, true), "pfcp-link");
                LOG.info("pfcp-ra recovery timestamp change peer={}", peer);
                reAssociate(peer);
                return;
            }
            p.peerRecovery = recovery;
        }
        fire(new PfcpHeartbeatEvent(peer, true), "pfcp-link");
    }

    private int nextSequence() {
        return seq.getAndIncrement() & 0xff_ffff;
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
