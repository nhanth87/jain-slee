package com.microjainslee.ra.gtpv2c;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.ra.gtpv2c.command.GtpEchoCommand;
import com.microjainslee.ra.gtpv2c.command.GtpOutboundCommand;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.gtpv2c.event.GtpEchoEvent;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JAINSLEE 3-port GTPv2-C RA. Connection only: UDP, codec, Echo,
 * ingress §7.6 response replay, outbound T3/N3 retransmit of requests we sent.
 * Create/Modify/Delete Session are fired to an SBB — never handled here.
 * Peer live = Echo Response only — bind/LISTEN is never live.
 *
 * <p>T3 default {@value #DEFAULT_T3_MS} ms, N3 default {@value #DEFAULT_N3}
 * retransmissions after the initial send (TS 29.274). Override via
 * {@link #Gtpv2RaEndpoint(byte, long, int)}. Responses are never retransmitted.
 */
public final class Gtpv2RaEndpoint implements RaEndpointPort, RaCommandPort {

    public static final String RA_NAME = "gtpv2c-ra";
    /** TS 29.274 retransmission timer — 3 seconds. */
    public static final long DEFAULT_T3_MS = Gtpv2T3N3.DEFAULT_T3_MS;
    /** TS 29.274 max retransmissions after the initial send — 3. */
    public static final int DEFAULT_N3 = Gtpv2T3N3.DEFAULT_N3;
    private static final Logger LOG = LogManager.getLogger(Gtpv2RaEndpoint.class);

    private final byte recovery;
    private final AtomicInteger seq = new AtomicInteger(1);
    /** TS 29.274 §7.6 — replay the <em>response</em>, never drop the request silently. */
    private final Map<String, byte[]> responseCache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, PeerState> peers = new ConcurrentHashMap<>();
    private final Gtpv2T3N3 t3n3;
    private volatile RaBootstrapPort bootstrap;
    private volatile boolean listening;
    private volatile OutboundSink sink = OutboundSink.NOOP;
    private volatile DatagramSocket socket;

    public Gtpv2RaEndpoint(byte recovery) {
        this(recovery, DEFAULT_T3_MS, DEFAULT_N3);
    }

    public Gtpv2RaEndpoint() {
        this((byte) 1);
    }

    /**
     * @param t3Ms retransmission timer (default {@value #DEFAULT_T3_MS})
     * @param n3 max retransmits after initial send (default {@value #DEFAULT_N3})
     */
    public Gtpv2RaEndpoint(byte recovery, long t3Ms, int n3) {
        this.recovery = recovery;
        this.t3n3 = new Gtpv2T3N3(t3Ms, n3, new Gtpv2T3N3.Handler() {
            @Override
            public void resend(byte[] wire, InetSocketAddress peer) {
                if (listening) {
                    emit(wire, peer);
                }
            }

            @Override
            public void echoExhausted(InetSocketAddress peer) {
                markPeerDown(peer, "t3-n3-exhausted");
            }

            @Override
            public void procedureExhausted(Gtpv2MessageType type, int sequence, InetSocketAddress peer) {
                LOG.debug("gtpv2c-ra T3/N3 exhausted type={} seq={} peer={}", type, sequence, peer);
            }
        });
    }

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
        LOG.info("gtpv2c-ra ACTIVE localListen=true live=false");
    }

    /** Optional UDP bind. Tests inject {@link #receive}; runtime binds :2123. */
    public void bindUdp(InetSocketAddress local) {
        Objects.requireNonNull(local);
        try {
            DatagramSocket s = new DatagramSocket(local);
            this.socket = s;
            Thread.ofVirtual().name("gtpv2c-udp-" + local.getPort()).start(() -> loop(s));
            LOG.info("gtpv2c-ra UDP bound {} live=false", local);
        } catch (Exception e) {
            LOG.warn("gtpv2c-ra UDP bind failed {}: {}", local, e.toString());
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
        responseCache.clear();
        inFlight.clear();
        t3n3.cancelAll();
        LOG.info("gtpv2c-ra INACTIVE");
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (!(command instanceof GtpOutboundCommand gtp)) {
            LOG.warn("gtpv2c-ra unknown command {}", command == null ? "null" : command.getClass());
            return;
        }
        switch (gtp) {
            case GtpEchoCommand echo -> {
                int s = seq.getAndIncrement() & 0xff_ffff;
                Gtpv2Message req = Gtpv2Message.echoRequest(s, recovery);
                byte[] wire = Gtpv2Codec.encode(req);
                t3n3.track(echo.peer(), s, req.type(), wire);
                emit(wire, echo.peer());
            }
            case SendGtpv2Message send -> {
                Gtpv2Message msg = send.message();
                byte[] wire = Gtpv2Codec.encode(msg);
                if (msg.type().isRequest()) {
                    t3n3.track(send.peer(), msg.sequence(), msg.type(), wire);
                } else {
                    cacheResponse(send.peer(), msg.sequence(), wire);
                }
                emit(wire, send.peer());
            }
        }
    }

    /**
     * Ingress from UDP (or test harness). Echo stays in the RA. Procedure
     * messages fire {@link Gtpv2MessageEvent} to the SLEE.
     */
    public void receive(byte[] wire, InetSocketAddress peer) {
        if (wire == null || wire.length < 8) {
            LOG.debug("gtpv2c-ra truncated");
            return;
        }
        int version = (wire[0] >> 5) & 0x07;
        if (version != 2) {
            emit(Gtpv2Codec.encode(Gtpv2Message.versionNotSupported(0)), peer);
            return;
        }
        Gtpv2Message msg;
        try {
            msg = Gtpv2Codec.decode(wire);
        } catch (IllegalArgumentException e) {
            LOG.debug("gtpv2c-ra decode {}", e.toString());
            return;
        }
        if (msg.type().isResponse()) {
            t3n3.complete(peer, msg.sequence());
        }
        if (msg.type() == Gtpv2MessageType.UNKNOWN
                || msg.type() == Gtpv2MessageType.VERSION_NOT_SUPPORTED) {
            LOG.debug("gtpv2c-ra ignore {}", msg.type());
            return;
        }
        String txKey = txKey(peer, msg.sequence());
        byte[] cached = responseCache.get(txKey);
        if (cached != null) {
            emit(cached, peer);
            return;
        }
        if (msg.type() == Gtpv2MessageType.ECHO_REQUEST) {
            byte[] rsp = Gtpv2Codec.encode(Gtpv2Message.echoResponse(msg.sequence(), recovery));
            cacheResponse(peer, msg.sequence(), rsp);
            emit(rsp, peer);
            return;
        }
        if (msg.type() == Gtpv2MessageType.ECHO_RESPONSE) {
            peers.put(peer.toString(), PeerState.up("gtp:" + peer, "echo-response recovery=" + (msg.recovery() & 0xff)));
            fire(new GtpEchoEvent(peer, msg.recovery(), true), "gtp-link");
            return;
        }
        if (!inFlight.add(txKey)) {
            return;
        }
        fire(new Gtpv2MessageEvent(msg, peer), "gtp-tx-" + msg.sequence());
    }

    public void markPeerDown(InetSocketAddress peer, String why) {
        peers.put(peer.toString(), PeerState.down("gtp:" + peer, listening, why));
    }

    public PeerState peer(InetSocketAddress peer) {
        return peers.getOrDefault(peer.toString(),
                PeerState.down("gtp:" + peer, listening, "no-echo"));
    }

    public boolean listening() {
        return listening;
    }

    public byte recovery() {
        return recovery;
    }

    public long t3Millis() {
        return t3n3.t3Millis();
    }

    public int n3() {
        return t3n3.n3();
    }

    public int outstandingCount() {
        return t3n3.size();
    }

    private void cacheResponse(InetSocketAddress peer, int sequence, byte[] wire) {
        String key = txKey(peer, sequence);
        responseCache.put(key, wire);
        inFlight.remove(key);
    }

    private static String txKey(InetSocketAddress peer, int sequence) {
        return peer + "/" + sequence;
    }

    private void emit(byte[] bytes, InetSocketAddress peer) {
        sink.send(bytes, peer);
        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) {
            try {
                s.send(new DatagramPacket(bytes, bytes.length, peer));
            } catch (Exception e) {
                LOG.debug("gtpv2c-ra send {}", e.toString());
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
                InetSocketAddress peer = (InetSocketAddress) packet.getSocketAddress();
                receive(wire, peer);
            } catch (Exception e) {
                if (!listening || s.isClosed()) {
                    return;
                }
                LOG.debug("gtpv2c-ra rx {}", e.toString());
            }
        }
    }

    private void fire(Gtpv2MessageEvent event, String activityId) {
        fireEvent(event, activityId);
    }

    private void fire(GtpEchoEvent event, String activityId) {
        fireEvent(event, activityId);
    }

    private void fireEvent(com.microjainslee.api.SleeEvent event, String activityId) {
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
