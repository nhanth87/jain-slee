/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.ra.diameter.collab.DiameterEventClassifier;
import com.microjainslee.ra.diameter.collab.DiameterOutboundSender;
import com.microjainslee.ra.diameter.collab.DiameterPeerTracker;
import com.microjainslee.ra.diameter.collab.DiameterPeerTracker.BaseAction;
import com.microjainslee.ra.diameter.command.DiameterCommand;
import com.microjainslee.ra.diameter.events.DiameterEvent;
import com.microjainslee.ra.diameter.transport.DiameterTransport;
import com.microjainslee.ra.diameter.transport.DiameterTransportCallbacks;
import com.microjainslee.ra.diameter.transport.TcpDiameterTransport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jdiameter.api.Avp;
import org.jdiameter.api.Message;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.impl.parser.MessageParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diameter Resource Adaptor — Netty TCP transport + 3-port contract.
 *
 * <p>Camel-generic: a single RA instance handles <em>any</em> Diameter
 * application (Cx/Dx, Sh, Gx, Ro) through a shared transport layer.
 * SBBs receive typed {@link DiameterEvent}s distinguished by
 * {@code applicationId} and {@code commandCode}.</p>
 *
 * <p>Lifecycle: raConfigure → raActive → (process Diameter) → raInactive → raUnconfigure.</p>
 *
 * <p><strong>Link-status truth:</strong> {@link #isActive()} means local RA/transport
 * lifecycle only (TCP LISTEN may be up). Peer UP / traffic-ready is
 * {@link #isPeerReady()} — CER/CEA success + live channel (+ optional watchdog).
 * Never map {@code isActive()} to Diameter link UP.</p>
 */
public final class DiameterResourceAdaptor implements DiameterTransportCallbacks {
    private static final Logger LOG = LogManager.getLogger(DiameterResourceAdaptor.class);

    private DiameterRaConfig config = new DiameterRaConfig();
    private final List<DiameterTransport> transports = new ArrayList<>(2);
    private RaBootstrapPort bootstrapPort;
    private DiameterEventClassifier classifier = new DiameterEventClassifier();
    private DiameterOutboundSender outboundSender;
    private DiameterPeerTracker peerTracker = new DiameterPeerTracker(30_000L);
    private MessageParser baseParser = new MessageParser();
    private final Map<String, ActivityHandle> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);

    // ---- collaborator injection ----

    public void setBootstrapPort(RaBootstrapPort bp) { this.bootstrapPort = bp; }
    public void setConfig(DiameterRaConfig c) {
        this.config = c;
        this.peerTracker = new DiameterPeerTracker(c.watchdogTimeoutMs());
    }
    public void setClassifier(DiameterEventClassifier c) { this.classifier = c; }
    public void setOutboundSender(DiameterOutboundSender s) { this.outboundSender = s; }
    /** Replace peer tracker (tests). */
    public void setPeerTracker(DiameterPeerTracker tracker) {
        this.peerTracker = tracker != null ? tracker : new DiameterPeerTracker(0);
    }

    // ---- accessors ----

    public DiameterRaConfig config() { return config; }

    /**
     * RA lifecycle / TCP listen started — <strong>not</strong> Diameter peer UP.
     * Use {@link #isPeerReady()} for CER/CEA + watchdog peer truth.
     */
    public boolean isActive() { return active.get(); }

    /**
     * At least one TCP peer channel is open — still not CER/CEA ready.
     * Distinct from {@link #isActive()} (local listen) and {@link #isPeerReady()}.
     */
    public boolean isPeerConnected() {
        return active.get() && peerTracker.isPeerConnected();
    }

    /**
     * True when at least one Diameter peer completed CER/CEA (Result-Code 2001),
     * the TCP channel is still up, and (if configured) the watchdog has not expired.
     * This is the honest “Diameter link UP / traffic-ready” primitive.
     */
    public boolean isPeerReady() {
        return active.get() && peerTracker.isPeerReady();
    }

    /** Status detail string for admin/health — never claims UP without CE. */
    public String peerDetail() {
        if (!active.get()) return "diameter:ra-inactive";
        return peerTracker.detail();
    }

    public DiameterPeerTracker peerTracker() { return peerTracker; }

    // ---- Lifecycle ----

    public void raConfigure() {
        peerTracker = new DiameterPeerTracker(config.watchdogTimeoutMs());
        LOG.info("[ra-diameter] raConfigure host={} port={} realm={} watchdogMs={}",
                config.host(), config.port(), config.realm(), config.watchdogTimeoutMs());
    }

    public void raActive() {
        if (!active.compareAndSet(false, true)) return;
        if (config.tcpEnabled()) {
            TcpDiameterTransport tcp = new TcpDiameterTransport(config, this);
            baseParser = tcp.parser();
            transports.add(tcp);
        }
        transports.forEach(DiameterTransport::start);
        LOG.info("[ra-diameter] ACTIVE transports={} (LISTEN ≠ peer UP; use isPeerReady())",
                transports.size());
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) return;
        transports.forEach(DiameterTransport::stop);
        transports.clear();
        sessions.clear();
        peerTracker.clear();
        LOG.info("[ra-diameter] INACTIVE");
    }

    public void raUnconfigure() {
        raInactive();
        LOG.info("[ra-diameter] UNCONFIGURED");
    }

    // ---- outbound (SBB → RA → wire) ----

    /** Called by {@code DiameterRaEndpoint.sendCommand} when an SBB
     * sends an outbound Diameter command. */
    public void sendOutbound(DiameterCommand cmd) {
        if (outboundSender != null) {
            outboundSender.send(cmd);
        } else {
            LOG.warn("[ra-diameter] No outbound sender, dropping {}",
                    cmd.getClass().getSimpleName());
        }
    }

    // ---- transport callbacks (wire → peer tracker → SLEE) ----

    @Override
    public void onPeerConnected(String peerId) {
        peerTracker.onTcpConnected(peerId);
        LOG.info("[ra-diameter] peer TCP connected id={} (awaiting CER/CEA)", peerId);
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        peerTracker.onTcpDisconnected(peerId);
        LOG.info("[ra-diameter] peer TCP disconnected id={} ready={}", peerId, isPeerReady());
    }

    @Override
    public void onMessage(String peerId, Message msg, MessageReplyWriter replyWriter) {
        if (!active.get()) return;

        long resultCode = extractResultCode(msg);
        BaseAction action = peerTracker.onInbound(
                peerId, msg.getCommandCode(), msg.isRequest(), resultCode);

        switch (action) {
            case ANSWER_CEA -> {
                if (replyBaseAnswer(msg, replyWriter, "CEA")) {
                    peerTracker.markCapabilitiesExchanged(peerId);
                    LOG.info("[ra-diameter] CER→CEA peer={} → peer-ready={}",
                            peerId, isPeerReady());
                }
                return;
            }
            case ANSWER_DWA -> {
                replyBaseAnswer(msg, replyWriter, "DWA");
                return;
            }
            case ANSWER_DPA -> {
                replyBaseAnswer(msg, replyWriter, "DPA");
                return;
            }
            case CONSUMED -> {
                return;
            }
            case NONE -> { /* application message */ }
        }

        if (bootstrapPort == null) return;

        String sessionId = extractSessionId(msg);
        ActivityHandle handle = sessions.computeIfAbsent(sessionId,
                id -> bootstrapPort.createActivityHandle(id));

        DiameterEvent event = classifier.classify(msg);
        if (event != null) {
            bootstrapPort.fireEvent(event, handle, null);
        }
    }

    /**
     * Test/harness entry: drive peer plane without Netty.
     * Call {@link #onPeerConnected(String)} first to simulate TCP accept.
     */
    public void ingestForTest(String peerId, Message msg, MessageReplyWriter replyWriter) {
        onMessage(peerId, msg, replyWriter != null ? replyWriter : a -> { });
    }

    private boolean replyBaseAnswer(Message request, MessageReplyWriter replyWriter, String kind) {
        if (replyWriter == null || !(request instanceof IMessage ireq)) {
            LOG.warn("[ra-diameter] cannot answer {} — missing writer or IMessage", kind);
            return false;
        }
        try {
            IMessage answer = baseParser.createEmptyMessage(ireq);
            answer.setRequest(false);
            answer.getAvps().addAvp(Avp.RESULT_CODE, DiameterPeerTracker.RESULT_SUCCESS, true);
            answer.getAvps().addAvp(Avp.ORIGIN_HOST, config.originHost(), true, false, true);
            answer.getAvps().addAvp(Avp.ORIGIN_REALM, config.realm(), true, false, true);
            if (config.productName() != null && !config.productName().isBlank()) {
                answer.getAvps().addAvp(Avp.PRODUCT_NAME, config.productName(), false);
            }
            replyWriter.write(answer);
            return true;
        } catch (Exception e) {
            LOG.warn("[ra-diameter] failed to build/send {}", kind, e);
            return false;
        }
    }

    private static long extractResultCode(Message msg) {
        if (msg.isRequest()) return -1L;
        try {
            Avp avp = msg.getAvps().getAvp(Avp.RESULT_CODE);
            if (avp != null) return avp.getUnsigned32();
        } catch (Exception ignored) { /* fall through */ }
        return -1L;
    }

    private static String extractSessionId(Message msg) {
        try {
            String sid = msg.getSessionId();
            if (sid != null && !sid.isBlank()) return sid;
        } catch (Exception ignored) { /* fall through */ }
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
