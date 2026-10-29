/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.RaHaSupport;
import com.microjainslee.cluster.RaStickyRouter;
import com.microjainslee.ra.sipservlet.collab.*;
import com.microjainslee.ra.sipservlet.command.SelectIceCandidate;
import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendMediaKeepAlive;
import com.microjainslee.ra.sipservlet.command.SendRegister;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.command.SipOutboundCommand;
import com.microjainslee.ra.sipservlet.command.StartIce;
import com.microjainslee.ra.sipservlet.dns.DnsResolver;
import com.microjainslee.ra.sipservlet.events.SipEvent;
import com.microjainslee.ra.sipservlet.stun.IceCandidateCollector;
import com.microjainslee.ra.sipservlet.stun.StunClient;
import com.microjainslee.ra.sipservlet.transport.*;

import gov.nist.javax.sip.message.SIPMessage;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.parser.StringMsgParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sip.header.CSeqHeader;
import javax.sip.message.Request;

import java.net.InetSocketAddress;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SIP-Servlet Resource Adaptor — Netty transports + 3-port contract.
 *
 * <p>Lifecycle: raConfigure → raActive → (process SIP) → raInactive → raUnconfigure.</p>
 *
 * <p>Inbound: transports push {@code (bytes, peer, transport)} into
 * {@link #onRawMessage}; messages are parsed, recorded in the
 * {@link DialogRegistry}, classified into typed {@link SipEvent}s and fired
 * through {@link RaBootstrapPort#fireEvent}.</p>
 *
 * <p>Outbound: SBB commands arrive via {@link #sendOutbound}. ICE commands
 * are handled here; SIP commands go to the {@link SipOutboundSender} —
 * by default a {@link NettySipOutboundSender} wired over the same
 * transports, so the RA answers out of the box.</p>
 *
 * <p>Dialog lifecycle: inbound BYE defers teardown until the SBB sends a
 * final response to BYE (RFC 3261 §15.1.2), or idle sweep expires the dialog.
 * Final non-2xx to INVITE ends immediately. Ending a dialog releases the
 * registry entry and calls {@link RaBootstrapPort#endActivity}.</p>
 */
public final class SipServletResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(SipServletResourceAdaptor.class);

    private SipRaConfig config = new SipRaConfig();
    private final Map<String, SipTransport> transports = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong retransmissionsAbsorbed =
            new java.util.concurrent.atomic.AtomicLong();
    private RaBootstrapPort bootstrapPort;
    private SipEventClassifier classifier = new DefaultSipEventClassifier();
    private SipOutboundSender outboundSender;
    private NettySipOutboundSender defaultSender;
    private final DialogRegistry dialogRegistry = new DialogRegistry();
    private final Map<String, ActivityHandle> dialogs = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private ScheduledExecutorService sweeper;
    private volatile ClientTransactions clientTransactions;

    // ---- DNS / STUN / ICE ----
    private DnsResolver dnsResolver;
    private StunClient stunClient;
    private IceCandidateCollector iceCollector;

    // ---- HA (ADR 0002 / Gate A) ----
    private volatile ClusterManager clusterManager;
    private volatile RaHaSupport haSupport;

    // ---- collaborator injection ----

    public void setBootstrapPort(RaBootstrapPort bp) {
        this.bootstrapPort = bp;
    }

    public void setConfig(SipRaConfig c) {
        this.config = c;
    }

    public void setClassifier(SipEventClassifier c) {
        this.classifier = c;
    }

    /** Override the default Netty-backed sender (tests, custom stacks). */
    public void setOutboundSender(SipOutboundSender s) {
        this.outboundSender = s;
    }

    /** Bind optional {@link ClusterManager} for sticky Call-ID ownership. */
    public void setClusterManager(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    /** Gate A — bind MicroSleeContainer for RA-driven SBB checkpoint. */
    public void setMicroSleeContainer(Object container) {
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.checkpointBridge().bindContainer(container);
        } else {
            // Defer until raActive creates haSupport
            pendingCheckpointContainer = container;
        }
    }

    private volatile Object pendingCheckpointContainer;

    // ---- accessors ----

    public SipRaConfig config() { return config; }
    /** RA lifecycle / transports started — **not** SIP peer registered or dialog-ready. */
    public boolean isActive() { return active.get(); }
    public DialogRegistry dialogRegistry() { return dialogRegistry; }
    public RaHaSupport haSupport() { return haSupport; }

    /** True when local DialogRegistry still holds Call-ID (local respawn path). */
    public boolean hasDialog(String callId) {
        return dialogRegistry.contains(callId);
    }

    /**
     * Failover priming: restore portable peers/cseq (+ synthetic wire when URI/tags present)
     * from RaSessionMeta ISPN attrs.
     *
     * @return true when peers were restored into the local DialogRegistry
     */
    public boolean restoreDialogFromCluster(String callId) {
        if (callId == null || callId.isBlank()) {
            return false;
        }
        if (dialogRegistry.contains(callId) && dialogRegistry.find(callId).lastRequest() != null) {
            return true;
        }
        RaHaSupport ha = haSupport;
        if (ha == null) {
            return false;
        }
        return ha.lookupMeta(callId).map(meta -> {
            DialogRegistry.PortableDialogMeta portable =
                    DialogRegistry.PortableDialogMeta.fromAttrs(callId, meta.attrs());
            return restoreDialog(portable);
        }).orElse(false);
    }

    /**
     * Restore DialogRegistry from portable meta (Elisa DialogCheckpoint / sticky attrs).
     * Lab-grade: rebuilds synthetic lastRequest/lastResponse when wire essentials present.
     */
    public boolean restoreDialog(DialogRegistry.PortableDialogMeta portable) {
        if (portable == null || portable.callId() == null || portable.callId().isBlank()) {
            return false;
        }
        String callId = portable.callId();
        ActivityHandle handle = dialogs.computeIfAbsent(callId, id -> {
            if (bootstrapPort != null) {
                return bootstrapPort.createActivityHandle(id);
            }
            return () -> id;
        });
        dialogRegistry.restorePortable(portable, handle);
        RaHaSupport ha = haSupport;
        if (ha != null) {
            if (ha.lookupOwner(callId).isEmpty() || !ha.isLocalOwner(callId)) {
                ha.onOpened(callId, "Recovered", portable.toAttrs());
            } else {
                ha.onTouched(callId, "Recovered", portable.toAttrs());
            }
        }
        LOG.info("[ra-sip-servlet] restored portable dialog callId={} peer={} wire={}",
                callId, portable.peerHost(), portable.hasWireEssentials());
        return dialogRegistry.contains(callId);
    }

    // ---- Lifecycle ----

    public void raConfigure() {
        LOG.info("[ra-sip-servlet] raConfigure host={} tcp={} udp={} sctp={} client={}",
                config.host(), config.tcpPort(), config.udpPort(),
                config.sctpPort(), config.clientEnabled());
    }

    public void raActive() {
        if (!active.compareAndSet(false, true)) return;
        if (config.tcpPort() > 0)
            registerTransport(new TcpTransport(config, this::onRawMessage));
        if (config.udpPort() > 0)
            registerTransport(new UdpTransport(config, this::onRawMessage));
        if (config.sctpPort() > 0)
            registerTransport(new SctpTransport(config, this::onRawMessage));
        if (config.tlsPort() > 0)
            registerTransport(new TlsTransport(config, this::onRawMessage));
        transports.values().forEach(SipTransport::start);

        // Default outbound path — SBB commands work with zero extra wiring.
        if (outboundSender == null) {
            defaultSender = new NettySipOutboundSender(config, dialogRegistry, transports);
            outboundSender = defaultSender;
            LOG.info("[ra-sip-servlet] default Netty outbound sender wired");
        }
        if (defaultSender != null) {
            NettySipOutboundSender sender = defaultSender;
            clientTransactions = new ClientTransactions(sender::retransmit, this::onClientTimeout);
            sender.setClientTransactions(clientTransactions);
        }

        // DNS resolver
        if (config.dnsEnabled()) {
            dnsResolver = new DnsResolver(true, config.dnsCacheTtlSecs());
        }
        // STUN client + ICE collector (signaling-only; TURN config for rtp_redirect prefer-relay)
        if (config.iceEnabled()) {
            if (config.stunServer() != null && !config.stunServer().isBlank()) {
                stunClient = new StunClient(config.stunServer(), config.stunPort());
                stunClient.startKeepAlive(config.iceKeepAliveSecs());
                LOG.info("[ra-sip-servlet] STUN client started server={}", config.stunServer());
            }
            iceCollector = new IceCandidateCollector(
                    stunClient,
                    config.turnServer(),
                    config.turnPort(),
                    config.preferRelayCandidate());
            iceCollector.setBootstrapPort(bootstrapPort);
            LOG.info("[ra-sip-servlet] ICE collector turn={} preferRelay={}",
                    config.turnServer(), config.preferRelayCandidate());
        }

        // Idle-dialog sweeper — dialogs abandoned without BYE must not leak.
        long sweepSecs = Math.max(1, config.dialogSweepIntervalSecs());
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sip-ra-dialog-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweepIdleDialogs, sweepSecs, sweepSecs, TimeUnit.SECONDS);

        initHa();
        LOG.info("[ra-sip-servlet] ACTIVE transports={}", transports.size());
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) return;
        teardownHa();
        if (sweeper != null) { sweeper.shutdownNow(); sweeper = null; }
        if (clientTransactions != null) { clientTransactions.stop(); clientTransactions = null; }
        transports.values().forEach(SipTransport::stop);
        transports.clear();
        dialogs.clear();
        dialogRegistry.clear();
        if (outboundSender == defaultSender) { outboundSender = null; }
        defaultSender = null;
        if (stunClient != null) { stunClient.close(); stunClient = null; }
        if (dnsResolver != null) { dnsResolver.clearCache(); dnsResolver = null; }
        iceCollector = null;
        LOG.info("[ra-sip-servlet] INACTIVE");
    }

    private void initHa() {
        ClusterManager cm = clusterManager;
        RaHaSupport ha = cm != null
                ? RaHaSupport.create(cm, "sip-servlet")
                : RaHaSupport.localOnly("sip-servlet", "local-sip");
        Object pending = pendingCheckpointContainer;
        if (pending != null) {
            ha.checkpointBridge().bindContainer(pending);
        }
        if (ha.isClustered()) {
            ha.startStickyBus(env -> {
                if (env.payload() instanceof SipOutboundCommand cmd) {
                    sendOutboundLocal(cmd);
                }
            });
        }
        haSupport = ha;
    }

    private void teardownHa() {
        RaHaSupport ha = haSupport;
        haSupport = null;
        if (ha != null) {
            ha.stopStickyBus();
        }
    }

    public void raUnconfigure() {
        raInactive();
        LOG.info("[ra-sip-servlet] UNCONFIGURED");
    }

    // ---- outbound (SBB → RA) ----

    /**
     * Called by {@code SipServletRaEndpoint.sendCommand} when an SBB
     * sends an outbound command. ICE commands are handled by the RA;
     * SIP commands are delegated to the outbound sender.
     */
    public void sendOutbound(SipOutboundCommand cmd) {
        if (cmd == null) return;
        RaHaSupport ha = haSupport;
        if (ha == null) {
            sendOutboundLocal(cmd);
            return;
        }
        boolean creating = cmd instanceof SendInvite || cmd instanceof SendRegister;
        // Transport up = honest route for SIP edge (listen ≠ peer registered).
        boolean routeReady = active.get() && !transports.isEmpty();
        RaStickyRouter.Decision d = ha.decide(cmd.callId(), creating, routeReady);
        switch (d.action()) {
            case REJECT -> LOG.warn("[ra-sip-servlet] sticky REJECT {}: {}",
                    cmd.getClass().getSimpleName(), d.reason());
            case FORWARD_REMOTE -> {
                if (d.owner() == null || !ha.forward(d.owner().ownerNodeId(), cmd.callId(), cmd)) {
                    LOG.warn("[ra-sip-servlet] sticky FORWARD failed callId={}", cmd.callId());
                }
            }
            case SEND_LOCAL -> sendOutboundLocal(cmd);
        }
    }

    void sendOutboundLocal(SipOutboundCommand cmd) {
        if (cmd == null) return;
        switch (cmd) {
            case StartIce c -> startIce(c.callId());
            case SelectIceCandidate c ->
                    LOG.debug("[ra-sip-servlet] ICE candidate selected callId={} {}:{} ({})",
                            c.callId(), c.address(), c.port(), c.type());
            case SendMediaKeepAlive c ->
                    LOG.debug("[ra-sip-servlet] media keep-alive {} for callId={}",
                            c.enable() ? "ON" : "OFF", c.callId());
            case SendResponse c -> {
                if (outboundSender != null) {
                    outboundSender.send(c);
                    maybeEndDialogAfterByeAck(c.callId(), c.statusCode());
                } else {
                    LOG.warn("[ra-sip-servlet] Outbound sender not configured, dropping: SendResponse");
                }
            }
            default -> {
                if (outboundSender != null) {
                    outboundSender.send(cmd);
                } else {
                    LOG.warn("[ra-sip-servlet] Outbound sender not configured, dropping: {}",
                            cmd.getClass().getSimpleName());
                }
            }
        }
        if (cmd instanceof SendInvite || cmd instanceof SendRegister) {
            publishHaOpened(cmd.callId(), null, null);
        }
    }

    /**
     * After SBB answers inbound BYE with a final response, tear down dialog
     * (transaction complete). Idle sweeper is the safety net if no 200 is sent.
     */
    private void maybeEndDialogAfterByeAck(String callId, int statusCode) {
        if (statusCode < 200 || callId == null) {
            return;
        }
        DialogRegistry.Dialog dialog = dialogRegistry.find(callId);
        if (dialog == null) {
            return;
        }
        SIPRequest last = dialog.lastRequest();
        if (last == null) {
            return;
        }
        if (Request.BYE.equals(last.getMethod())
                || (Request.INVITE.equals(last.getMethod()) && statusCode >= 300)) {
            endDialog(callId);
        }
    }

    private void startIce(String callId) {
        IceCandidateCollector collector = this.iceCollector;
        if (collector == null) {
            LOG.warn("[ra-sip-servlet] StartIce for callId={} but ICE is disabled", callId);
            return;
        }
        collector.gatherAll().whenComplete((candidates, error) -> {
            if (error != null) {
                LOG.warn("[ra-sip-servlet] ICE gathering failed for callId={}", callId, error);
            } else {
                collector.fireCandidates(callId, candidates);
            }
        });
    }

    // ---- inbound (transport → SLEE) ----

    void onRawMessage(byte[] raw, InetSocketAddress peer, String transport) {
        if (!active.get()) return;
        try {
            StringMsgParser parser = new StringMsgParser();
            SIPMessage sipMsg = parser.parseSIPMessage(raw, true, false, null);
            if (sipMsg != null) onSipMessage(sipMsg, peer, transport);
        } catch (ParseException e) {
            LOG.warn("[ra-sip-servlet] Parse error from {}", peer, e);
        }
    }

    private void onSipMessage(SIPMessage msg, InetSocketAddress peer, String transport) {
        if (bootstrapPort == null) {
            LOG.warn("[ra-sip-servlet] bootstrapPort not set, dropping message");
            return;
        }
        String callId = deriveCallId(msg);
        ClientTransactions ctx = clientTransactions;
        if (ctx != null && msg instanceof SIPResponse resp) {
            ctx.onResponse(resp);
        }
        if (absorbRetransmission(msg, callId)) {
            return;
        }
        if (config.autoTrying() && msg instanceof SIPRequest req && Request.INVITE.equals(req.getMethod())) {
            sendTrying(req, peer, transport, callId);
        }
        ActivityHandle handle = dialogs.computeIfAbsent(callId,
                id -> bootstrapPort.createActivityHandle(id));
        dialogRegistry.recordInbound(callId, handle, msg, peer, transport);
        publishHaOpened(callId, peer, transport);

        SipEvent event = classifier.classify(msg, callId, peer, transport);
        if (event != null) {
            bootstrapPort.fireEvent(event, handle, null);
        }

        if (isDialogTerminating(msg)) {
            // Inbound BYE: keep dialog until SBB sends final response (see sendOutbound).
            if (msg instanceof SIPRequest req && Request.BYE.equals(req.getMethod())) {
                return;
            }
            // Far leg's 2xx to the BYE the SBB relayed while the near leg's inbound BYE is
            // still unanswered: the SBB's final response ends the dialog
            // (maybeEndDialogAfterByeAck). Ending here races it — under load the trunk
            // answers before the SBB sends its 200, which then finds no dialog/owner.
            if (msg instanceof SIPResponse && inboundByePending(callId)) {
                return;
            }
            endDialog(callId);
        }
    }

    private void publishHaOpened(String callId, InetSocketAddress peer, String transport) {
        RaHaSupport ha = haSupport;
        if (ha == null || callId == null) {
            return;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        DialogRegistry.PortableDialogMeta portable = dialogRegistry.exportPortable(callId);
        if (portable != null) {
            attrs.putAll(portable.toAttrs());
        } else {
            if (peer != null) {
                attrs.put("peer", peer.getAddress().getHostAddress() + ":" + peer.getPort());
                attrs.put("peerHost", peer.getAddress().getHostAddress());
                attrs.put("peerPort", Integer.toString(peer.getPort()));
            }
            if (transport != null) {
                attrs.put("transport", transport);
            }
        }
        try {
            if (ha.lookupOwner(callId).isEmpty()) {
                ha.onOpened(callId, "Active", attrs);
            } else {
                ha.onTouched(callId, "Active", attrs);
            }
            ha.checkpointSbb(callId);
        } catch (RuntimeException ex) {
            LOG.warn("[ra-sip-servlet] HA publish failed callId={}: {}", callId, ex.toString());
        }
    }

    /**
     * The RA is the SIP transaction layer (RFC 3261 §17): retransmissions never become
     * SLEE events.
     * <ul>
     *   <li>Request retransmission (same Call-ID / Via branch / CSeq / method, §17.2.3):
     *       re-send the last response of that server transaction, if any.</li>
     *   <li>Retransmitted 2xx to our INVITE (§13.2.2.4): re-send the ACK already sent;
     *       the SBB saw the first copy.</li>
     * </ul>
     * ACK is its own transaction (no response) and always passes.
     */
    private boolean absorbRetransmission(SIPMessage msg, String callId) {
        if (msg instanceof SIPRequest req) {
            if (Request.ACK.equals(req.getMethod())) {
                return false;
            }
            DialogRegistry.ServerTransaction existing = dialogRegistry.beginServerTransaction(req);
            if (existing == null) {
                return false;
            }
            if (defaultSender != null) {
                defaultSender.retransmit(existing.lastResponse());
            }
            retransmissionsAbsorbed.incrementAndGet();
            LOG.debug("[ra-sip-servlet] {} retransmission absorbed callId={}", req.getMethod(), callId);
            return true;
        }
        if (msg instanceof SIPResponse resp && resp.getStatusCode() / 100 == 2
                && resp.getCSeq() != null && Request.INVITE.equals(resp.getCSeq().getMethod())) {
            DialogRegistry.Dialog dialog = dialogRegistry.find(callId);
            if (dialog == null || dialog.firstInvite2xx(resp)) {
                return false;
            }
            if (defaultSender != null) {
                defaultSender.retransmit(dialog.ackFor(resp));
            }
            retransmissionsAbsorbed.incrementAndGet();
            LOG.debug("[ra-sip-servlet] 2xx INVITE retransmission absorbed callId={}", callId);
            return true;
        }
        return false;
    }

    private void sendTrying(SIPRequest invite, InetSocketAddress peer, String transport, String callId) {
        NettySipOutboundSender sender = defaultSender;
        if (sender == null) {
            return;
        }
        try {
            sender.sendTrying(invite, peer, transport);
        } catch (Exception ex) {
            LOG.warn("[ra-sip-servlet] 100 Trying failed callId={}: {}", callId, ex.toString());
        }
    }

    /**
     * Timer B / F (RFC 3261 §17.1.1.2, §17.1.2.2): the TU sees a locally generated 408
     * (§8.1.3.1) on the dialog's activity, so it can fail the other leg. A transaction of a
     * dialog that is already gone (BYE after teardown) just ends.
     */
    private void onClientTimeout(SIPRequest request, DialogRegistry.SentMessage sent) {
        String callId = request.getCallId() != null ? request.getCallId().getCallId() : null;
        if (callId == null || !dialogRegistry.contains(callId) || !active.get()) {
            LOG.debug("[ra-sip-servlet] {} timed out after dialog end callId={}", request.getMethod(), callId);
            return;
        }
        LOG.info("[ra-sip-servlet] {} timed out (no response in {} ms) peer={} callId={}",
                request.getMethod(), ClientTransactions.TIMEOUT_MILLIS, sent.peer(), callId);
        onSipMessage(request.createResponse(408, "Request Timeout"), sent.peer(), sent.transport());
    }

    /** UDP request retransmissions sent by the client transaction layer. */
    public long clientRetransmissions() {
        ClientTransactions ctx = clientTransactions;
        return ctx == null ? 0L : ctx.retransmissions();
    }

    /** Client transactions ended by Timer B / F. */
    public long clientTimeouts() {
        ClientTransactions ctx = clientTransactions;
        return ctx == null ? 0L : ctx.timeouts();
    }

    /** Request / 2xx retransmissions handled inside the RA (never fired as events). */
    public long retransmissionsAbsorbed() {
        return retransmissionsAbsorbed.get();
    }

    private boolean inboundByePending(String callId) {
        DialogRegistry.Dialog dialog = dialogRegistry.find(callId);
        SIPRequest last = dialog == null ? null : dialog.lastRequest();
        return last != null && Request.BYE.equals(last.getMethod());
    }

    /** BYE requests and final non-2xx INVITE responses terminate the dialog. */
    private static boolean isDialogTerminating(SIPMessage msg) {
        if (msg instanceof SIPRequest req) {
            return Request.BYE.equals(req.getMethod());
        }
        if (msg instanceof SIPResponse resp) {
            int status = resp.getStatusCode();
            CSeqHeader cseq = (CSeqHeader) resp.getHeader(CSeqHeader.NAME);
            String method = cseq != null ? cseq.getMethod() : null;
            if (Request.BYE.equals(method) && status >= 200) {
                return true;
            }
            // INVITE 3xx–6xx must be relayed by P/I before teardown (REGISTER 401 is not INVITE).
            return false;
        }
        return false;
    }

    /** Tear down all per-dialog state and end the SLEE activity. */
    public void endDialog(String callId) {
        if (callId == null) return;
        dialogRegistry.remove(callId);
        if (defaultSender != null) {
            defaultSender.forgetDialog(callId);
        }
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.onClosed(callId);
        }
        ActivityHandle handle = dialogs.remove(callId);
        if (handle != null && bootstrapPort != null) {
            bootstrapPort.endActivity(handle);
            LOG.debug("[ra-sip-servlet] dialog ended callId={}", callId);
        }
    }

    private void sweepIdleDialogs() {
        try {
            dialogRegistry.sweepServerTransactions(System.currentTimeMillis());
            long idleMillis = TimeUnit.SECONDS.toMillis(Math.max(1, config.dialogIdleSecs()));
            for (DialogRegistry.Dialog dialog : dialogRegistry.expireIdle(idleMillis)) {
                LOG.info("[ra-sip-servlet] expiring idle dialog callId={} (idle>{}s)",
                        dialog.callId, config.dialogIdleSecs());
                endDialog(dialog.callId);
            }
        } catch (RuntimeException e) {
            LOG.warn("[ra-sip-servlet] dialog sweep failed", e);
        }
    }

    private void registerTransport(SipTransport transport) {
        transports.put(transport.protocol(), transport);
    }

    // ---- helpers ----

    /**
     * Extract Call-ID using javax.sip.* API (standard, not NIST-specific).
     * The NIST SIPMessage implements javax.sip.message.Message, so
     * {@code msg.getHeader(CallIdHeader.NAME)} works on all message types.
     */
    private static String deriveCallId(SIPMessage msg) {
        javax.sip.header.CallIdHeader callIdHdr =
                (javax.sip.header.CallIdHeader) msg.getHeader(
                        javax.sip.header.CallIdHeader.NAME);
        if (callIdHdr != null) {
            String cid = callIdHdr.getCallId();
            if (cid != null && !cid.isBlank()) return cid;
        }
        return UUID.randomUUID().toString();
    }
}
