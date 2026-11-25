package com.microjainslee.ra.pfcp;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.RaHaSupport;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.command.PfcpHeartbeatCommand;
import com.microjainslee.ra.pfcp.command.PfcpOutboundCommand;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import com.microjainslee.ra.pfcp.command.PfcpSessionAnswer;
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
import java.util.ArrayList;
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
    /** Seconds from 1900-01-01 (NTP) to 1970-01-01 (Unix). */
    static final long NTP_UNIX_OFFSET = 2_208_988_800L;
    private static final Logger LOG = LogManager.getLogger(PfcpRaEndpoint.class);

    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<String, PfcpPeer> peers = new ConcurrentHashMap<>();
    /** CP SEIDs this RA has established (either command form). */
    private final Map<Long, PfcpOutboundCommand> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, InetSocketAddress> pendingAssoc = new ConcurrentHashMap<>();
    /**
     * CP SEID → the UP's SEID from its Establishment Response F-SEID (TS 29.244 §7.2.2.4.2:
     * requests carry the receiver's SEID). SBBs keep using their own CP SEID; the RA
     * translates on Modification / Deletion.
     */
    private final Map<Long, Long> upSeids = new ConcurrentHashMap<>();
    private volatile RaBootstrapPort bootstrap;
    private volatile boolean listening;
    private volatile OutboundSink sink = OutboundSink.NOOP;
    private volatile DatagramSocket socket;

    private volatile PfcpNodeId nodeId = PfcpNodeId.of(InetAddress.getLoopbackAddress());
    /** §8.2.65: NTP seconds (epoch 1900). */
    private volatile int recoverySeconds = (int) (System.currentTimeMillis() / 1000L + NTP_UNIX_OFFSET);
    private volatile long heartbeatIntervalMs = PfcpHeartbeatDriver.DEFAULT_INTERVAL_MS;
    private volatile long heartbeatTimeoutMs = PfcpHeartbeatDriver.DEFAULT_TIMEOUT_MS;
    private volatile PfcpHeartbeatDriver heartbeats;
    private volatile ClusterManager clusterManager;
    private volatile RaHaSupport haSupport;
    private volatile Object pendingCheckpointContainer;

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

    /** Session ownership in {@code ra-pfcp-owner} (ADR 0002); null = single node. */
    public void setClusterManager(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    public void setMicroSleeContainer(Object container) {
        pendingCheckpointContainer = container;
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.checkpointBridge().bindContainer(container);
        }
    }

    public RaHaSupport haSupport() {
        return haSupport;
    }

    /** Session activity key for HA ownership: the CP SEID. */
    public static String sessionKey(long cpSeid) {
        return "seid-" + Long.toHexString(cpSeid);
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
        ClusterManager cm = clusterManager;
        RaHaSupport ha = cm != null ? RaHaSupport.create(cm, "pfcp") : RaHaSupport.localOnly("pfcp", "local-pfcp");
        if (pendingCheckpointContainer != null) {
            ha.checkpointBridge().bindContainer(pendingCheckpointContainer);
        }
        haSupport = ha;
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
        upSeids.clear();
        pendingAssoc.clear();
        RaHaSupport ha = haSupport;
        haSupport = null;
        if (ha != null) {
            ha.stopStickyBus();
        }
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
            case PfcpSessionEstablishment est -> {
                // §7.5.2.1: Node ID and CP F-SEID are mandatory; header SEID 0 (§7.2.2.4.2).
                List<PfcpIe> ies = new ArrayList<>();
                if (PfcpIes.find(est.ies(), PfcpIe.NODE_ID) == null) {
                    ies.add(PfcpIes.nodeId(nodeId));
                }
                if (PfcpIes.find(est.ies(), PfcpIe.F_SEID) == null) {
                    ies.add(PfcpIes.fSeid(est.seid(), signallingAddress()));
                }
                ies.addAll(est.ies());
                sessions.put(est.seid(), est);
                openSession(est.seid(), Map.of("upf", est.upf().toString()));
                sendSession(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, est.upf(), 0L, ies);
            }
            case PfcpSessionModification mod -> sendSession(
                    PfcpMessageType.SESSION_MODIFICATION_REQUEST, mod.upf(), upSeid(mod.seid()), mod.ies());
            case PfcpSessionDeletion del -> {
                sendSession(PfcpMessageType.SESSION_DELETION_REQUEST, del.upf(), upSeid(del.seid()), del.ies());
                upSeids.remove(del.seid());
                sessions.remove(del.seid());
                closeSession(del.seid());
            }
            case PfcpSessionReport rep -> sendSession(
                    PfcpMessageType.SESSION_REPORT_REQUEST, rep.upf(), rep.seid(), rep.ies());
            case PfcpProgramSession program -> {
                // §7.5.2.1: Node ID, CP F-SEID, Create PDR / FAR (+ QER / URR) from the rule bundle.
                sessions.put(program.seid(), program);
                openSession(program.seid(), Map.of("upf", program.upf().toString()));
                List<PfcpIe> ies = new ArrayList<>();
                ies.add(PfcpIes.nodeId(nodeId));
                ies.add(PfcpIes.fSeid(program.seid(), signallingAddress()));
                ies.addAll(PfcpIes.fromRules(program.rules(), program.ueIpv4()));
                sendSession(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, program.upf(), 0L, ies);
            }
            case PfcpSessionAnswer answer -> answer(answer);
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
            case SESSION_ESTABLISHMENT_RESPONSE -> {
                rememberUpSeid(msg);
                claimSession(msg, peer);
                fire(new PfcpMessageEvent(msg, peer), "pfcp-se-" + Long.toUnsignedString(msg.seid()));
            }
            default -> {
                claimSession(msg, peer);
                fire(new PfcpMessageEvent(msg, peer),
                    "pfcp-se-" + Long.toUnsignedString(msg.seid()));
            }
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

    /** The SEID to put in a request header for this CP SEID: the UP's, once its F-SEID is known. */
    private long upSeid(long cpSeid) {
        Long up = upSeids.get(cpSeid);
        if (up == null) {
            // Another node set the session up: its UP SEID rides in the replicated session meta.
            RaHaSupport ha = haSupport;
            String fromMeta = ha == null ? null : ha.lookupMeta(sessionKey(cpSeid))
                    .map(m -> m.attr("upSeid")).orElse(null);
            if (fromMeta != null) {
                up = Long.parseUnsignedLong(fromMeta, 16);
                upSeids.put(cpSeid, up);
            }
        }
        return up == null ? cpSeid : up;
    }

    private void openSession(long cpSeid, Map<String, String> attrs) {
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.onOpened(sessionKey(cpSeid), "Establishing", attrs);
        }
    }

    private void closeSession(long cpSeid) {
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.onClosed(sessionKey(cpSeid));
        }
    }

    /** Inbound session message: own (or take over) the session; a Deletion closes it. */
    private void claimSession(PfcpMessage msg, InetSocketAddress peer) {
        RaHaSupport ha = haSupport;
        if (ha == null || !msg.type().seidInHeader()) {
            return;
        }
        long cp;
        try {
            cp = msg.sessionSeid();
        } catch (RuntimeException e) {
            return;
        }
        if (msg.type() == PfcpMessageType.SESSION_DELETION_REQUEST
                || msg.type() == PfcpMessageType.SESSION_DELETION_RESPONSE) {
            ha.onClosed(sessionKey(cp));
            return;
        }
        Map<String, String> attrs = new java.util.HashMap<>();
        attrs.put("peer", peer.toString());
        Long up = upSeids.get(cp);
        if (up != null) {
            attrs.put("upSeid", Long.toHexString(up));
        }
        ha.claimOnInbound(sessionKey(cp), msg.type().name(), attrs);
    }

    private void rememberUpSeid(PfcpMessage resp) {
        try {
            PfcpIe fseid = resp.first(PfcpIe.F_SEID);
            if (fseid != null && resp.seid() != 0) {
                upSeids.put(resp.seid(), PfcpIes.parseFSeid(fseid).seid());
            }
            // claimSession (fired right after) copies upSeid into the replicated meta.
        } catch (RuntimeException e) {
            LOG.debug("pfcp-ra establishment response F-SEID {}", e.toString());
        }
    }

    /**
     * UP-side response (§7.5.3.1 / §7.5.5.1 / §7.5.7.1). The UP SEID equals the CP SEID
     * the request announced (this RA's UP allocates no SEID space of its own), so the
     * response header and the UP F-SEID both carry the CP's SEID.
     */
    private void answer(PfcpSessionAnswer a) {
        PfcpMessage req = a.request();
        long cpSeid = req.seid();
        PfcpIe cpFseid = req.first(PfcpIe.F_SEID);
        if (cpFseid != null) {
            cpSeid = PfcpIes.parseFSeid(cpFseid).seid();
        }
        PfcpMessageType type = switch (req.type()) {
            case SESSION_ESTABLISHMENT_REQUEST -> PfcpMessageType.SESSION_ESTABLISHMENT_RESPONSE;
            case SESSION_MODIFICATION_REQUEST -> PfcpMessageType.SESSION_MODIFICATION_RESPONSE;
            case SESSION_DELETION_REQUEST -> PfcpMessageType.SESSION_DELETION_RESPONSE;
            default -> throw new IllegalArgumentException("not a session request: " + req.type());
        };
        List<PfcpIe> ies = new ArrayList<>();
        if (type == PfcpMessageType.SESSION_ESTABLISHMENT_RESPONSE) {
            ies.add(PfcpIes.nodeId(nodeId));
        }
        ies.add(PfcpIes.cause(a.cause()));
        if (type == PfcpMessageType.SESSION_ESTABLISHMENT_RESPONSE
                && a.cause() == PfcpIe.CAUSE_REQUEST_ACCEPTED) {
            ies.add(PfcpIes.fSeid(cpSeid, signallingAddress()));
        }
        ies.addAll(a.ies());
        emit(PfcpCodec.encode(new PfcpMessage(type, cpSeid, req.sequence(), PfcpCodec.encodeIes(ies))), a.peer());
    }

    /** IPv4 for F-SEIDs: the Node ID address, else the bound UDP address, else loopback. */
    private InetAddress signallingAddress() {
        InetAddress v4 = PfcpIes.ipv4Of(nodeId);
        if (v4 != null) {
            return v4;
        }
        DatagramSocket s = socket;
        if (s != null && s.getLocalAddress() != null && !s.getLocalAddress().isAnyLocalAddress()) {
            return s.getLocalAddress();
        }
        return InetAddress.getLoopbackAddress();
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
