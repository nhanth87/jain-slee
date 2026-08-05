/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.ra.jss7.cluster.IspnStickyCommandBus;
import com.microjainslee.ra.jss7.cluster.Jss7TcapDialogFailoverPort;
import com.microjainslee.ra.jss7.cluster.Ss7DialogOwnershipTracker;
import com.microjainslee.ra.jss7.cluster.StickyRaCommandRouter;
import com.microjainslee.ra.jss7.cluster.TcapDialogFailoverPort;
import com.microjainslee.ra.jss7.cluster.TcapFailoverMetrics;
import com.microjainslee.ra.jss7.collab.CapProtocolAdapter;
import com.microjainslee.ra.jss7.collab.MapProtocolAdapter;
import com.microjainslee.ra.jss7.collab.Ss7EventPublisher;
import com.microjainslee.ra.jss7.collab.Ss7ProtocolAdapter;
import com.microjainslee.ra.jss7.collab.Ss7TcapListener;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.event.Ss7Event;
import com.microjainslee.ra.jss7.transport.Ss7Stack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.config.Ss7Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * jSS7 Resource Adaptor — owns the full jSS7 stack (SCTP → M3UA → SCCP →
 * TCAP → MAP/CAP) and bridges it to the SLEE event bus.
 *
 * <p>Inbound: protocol adapters ({@link Ss7TcapListener}, {@link MapProtocolAdapter},
 * {@link CapProtocolAdapter}) register listeners against the stack and publish
 * typed events through {@link #publish(String, SleeEvent)}. Outbound: SBB
 * commands are sticky-routed ({@link StickyRaCommandRouter}) then adapters /
 * TCAP.</p>
 *
 * <p><strong>n-n / failover (P1 + P2 wire):</strong> write-through dialog ownership via
 * {@link Ss7DialogOwnershipTracker}; sticky outbound to owner RA over the same
 * {@link ClusterManager} fabric ({@link IspnStickyCommandBus}). P2 wires
 * {@link TcapDialogFailoverPort} / jSS7 {@code exportDialog}/{@code importDialog}
 * + CONTINUE-miss resolver — <em>not</em> full STP multi-ASP lab HA.
 * Delivery gates must use {@link #isM3uaRouteReady()}, never {@link #isActive()} alone.</p>
 */
public final class Ss7ResourceAdaptor implements AutoCloseable, Ss7EventPublisher {

    private static final Logger LOG = LogManager.getLogger(Ss7ResourceAdaptor.class);

    private volatile RaBootstrapPort bootstrap;
    private volatile Ss7RaConfig config = new Ss7RaConfig();
    /** When set, preferred over flat {@link Ss7RaConfig} (multi-link JSON path). */
    private volatile Ss7Config ss7Config;
    private volatile Ss7Stack stack;
    /** Optional — when null, ownership stays JVM-local only. */
    private volatile ClusterManager clusterManager;
    private volatile String raName = "ra-jss7";

    private final List<Ss7ProtocolAdapter> adapters = new ArrayList<>();
    private final Map<String, MutableSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final IdleSweeper sweeper = new IdleSweeper();
    private int idleTimeoutSeconds = 300;

    private volatile Ss7DialogOwnershipTracker ownershipTracker;
    private volatile StickyRaCommandRouter stickyRouter;
    private volatile IspnStickyCommandBus stickyBus;
    private volatile TcapDialogFailoverPort failoverPort;
    private final TcapFailoverMetrics failoverMetrics = new TcapFailoverMetrics();

    // ── configuration ────────────────────────────────────────
    public void setBootstrapPort(RaBootstrapPort bp) { this.bootstrap = bp; }
    public RaBootstrapPort bootstrap() { return bootstrap; }
    public void setConfig(Ss7RaConfig cfg) { this.config = cfg; this.ss7Config = null; }
    public Ss7RaConfig config() { return config; }
    /** Full jSS7-25 JSON model — multi SCTP links / multi AS. Clears flat override. */
    public void setSs7Config(Ss7Config cfg) { this.ss7Config = cfg; }
    public Ss7Config ss7Config() { return ss7Config; }
    public void setIdleTimeoutSeconds(int s) { this.idleTimeoutSeconds = s; }
    public Ss7Stack stack() { return stack; }

    /**
     * Bind optional {@link ClusterManager} for ISPN dialog meta / sticky bus.
     * Call before {@link #raActive()}. Null = local-only ownership.
     */
    public void setClusterManager(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    public ClusterManager clusterManager() {
        return clusterManager;
    }

    public void setRaName(String raName) {
        if (raName != null && !raName.isBlank()) {
            this.raName = raName;
        }
    }

    public String raName() {
        return raName;
    }

    /** Package / test access. */
    public Ss7DialogOwnershipTracker ownershipTracker() {
        return ownershipTracker;
    }

    public StickyRaCommandRouter stickyRouter() {
        return stickyRouter;
    }

    /** P2 failover port (wired when stack + ownership are up; else unsupported). */
    public TcapDialogFailoverPort failoverPort() {
        TcapDialogFailoverPort p = failoverPort;
        return p != null ? p : TcapDialogFailoverPort.unsupported();
    }

    /** Lab / scrape: ADR 0001 P2 counters (export, import fail, sticky miss, …). */
    public TcapFailoverMetrics failoverMetrics() {
        return failoverMetrics;
    }

    /** RA lifecycle active — **not** peer route-ready; use {@link #isM3uaRouteReady()}. */
    public boolean isActive() { return active.get(); }

    /**
     * True when M3UA can route outbound MAP/CAP (SCTP association up and at least
     * one AS ACTIVE). Same truth OTA uses for {@code ss7.live} / scheduler gates.
     */
    public boolean isM3uaRouteReady() {
        Ss7Stack s = stack;
        return active.get() && s != null && s.isSignalingRouteReady();
    }

    // ── lifecycle ────────────────────────────────────────────
    public void raActive() {
        if (!active.compareAndSet(false, true)) return;
        try {
            this.stack = ss7Config != null ? new Ss7Stack(ss7Config) : new Ss7Stack(config);
            this.stack.start();
            logOtidRangeGuidance();

            boolean mapOn = ss7Config != null
                    ? (ss7Config.protocols() != null && Boolean.TRUE.equals(ss7Config.protocols().map()))
                    : config.mapEnabled();
            boolean capOn = ss7Config != null
                    ? (ss7Config.protocols() != null && Boolean.TRUE.equals(ss7Config.protocols().cap()))
                    : config.capEnabled();

            adapters.clear();
            adapters.add(new Ss7TcapListener());
            if (mapOn) adapters.add(new MapProtocolAdapter());
            if (capOn) adapters.add(new CapProtocolAdapter());
            for (Ss7ProtocolAdapter a : adapters) {
                a.attach(stack, this);
            }

            initOwnershipAndStickyBus();
            sweeper.start(idleTimeoutSeconds);
            LOG.info("jSS7 RA activated (adapters={}, idleTimeout={}s, fullConfig={}, clustered={})",
                    adapters.size(), idleTimeoutSeconds, ss7Config != null,
                    ownershipTracker != null && ownershipTracker.isClustered());
        } catch (Exception e) {
            active.set(false);
            teardownOwnership();
            LOG.error("jSS7 RA activation failed", e);
            throw new IllegalStateException("jSS7 RA activation failed", e);
        }
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) return;
        sweeper.stop();
        for (Ss7ProtocolAdapter a : adapters) {
            try { a.detach(); } catch (RuntimeException e) { LOG.warn("detach {} failed", a.protocol(), e); }
        }
        adapters.clear();
        clearMissingDialogResolver();
        if (stack != null) { stack.stop(); stack = null; }
        sessions.values().forEach(this::endActivity);
        sessions.clear();
        if (ownershipTracker != null) {
            ownershipTracker.clearAll();
        }
        teardownOwnership();
        LOG.info("jSS7 RA deactivated");
    }

    private void initOwnershipAndStickyBus() {
        String nodeId = clusterManager != null
                ? clusterManager.getNodeId()
                : "local-" + UUID.randomUUID().toString().substring(0, 8);
        int opc = ss7Config != null && ss7Config.sccp() != null
                && ss7Config.sccp().localPoints() != null
                && !ss7Config.sccp().localPoints().isEmpty()
                ? ss7Config.sccp().localPoints().get(0).pc()
                : config.originatingPointCode();
        int ssn = ss7Config != null && ss7Config.services() != null
                && !ss7Config.services().isEmpty()
                ? ss7Config.services().get(0).ssn()
                : config.localSsn();

        Ss7DialogClusterCaches caches = null;
        if (clusterManager != null) {
            caches = Ss7DialogClusterCaches.ensureCaches(clusterManager);
        }
        ownershipTracker = new Ss7DialogOwnershipTracker(nodeId, raName, opc, ssn, caches);
        stickyRouter = new StickyRaCommandRouter(ownershipTracker);
        if (caches != null) {
            stickyBus = new IspnStickyCommandBus(nodeId, caches, this::sendOutboundLocal);
            stickyBus.start();
        } else {
            stickyBus = null;
        }
        wireFailoverPort(caches);
    }

    private void wireFailoverPort(Ss7DialogClusterCaches caches) {
        Ss7Stack s = stack;
        if (s == null || ownershipTracker == null) {
            failoverPort = TcapDialogFailoverPort.unsupported();
            return;
        }
        Jss7TcapDialogFailoverPort wired = new Jss7TcapDialogFailoverPort(
                () -> {
                    Ss7Stack st = stack;
                    return st != null ? st.tcapProvider() : null;
                },
                () -> {
                    Ss7Stack st = stack;
                    return st != null && st.sccpProvider() != null
                            ? st.sccpProvider().getParameterFactory()
                            : null;
                },
                ownershipTracker,
                caches,
                failoverMetrics);
        failoverPort = wired;
        try {
            if (s.tcapProvider() != null) {
                s.tcapProvider().setMissingDialogResolver(wired);
                LOG.info("[ra-jss7] P2 TCAP failover port wired (CONTINUE-miss resolver on)");
            }
        } catch (RuntimeException e) {
            LOG.warn("[ra-jss7] failed to register MissingDialogResolver: {}", e.toString());
        }
    }

    private void teardownOwnership() {
        IspnStickyCommandBus bus = stickyBus;
        stickyBus = null;
        if (bus != null) {
            try {
                bus.stop();
            } catch (RuntimeException e) {
                LOG.warn("sticky bus stop failed: {}", e.toString());
            }
        }
        stickyRouter = null;
        ownershipTracker = null;
        failoverPort = null;
    }

    private void clearMissingDialogResolver() {
        Ss7Stack s = stack;
        if (s == null) {
            return;
        }
        try {
            if (s.tcapProvider() != null) {
                s.tcapProvider().setMissingDialogResolver(null);
            }
        } catch (RuntimeException e) {
            LOG.debug("[ra-jss7] clear MissingDialogResolver: {}", e.toString());
        }
    }

    private void logOtidRangeGuidance() {
        long start;
        long end;
        if (ss7Config != null && ss7Config.tcap() != null) {
            start = ss7Config.tcap().dialogIdRangeStart();
            end = ss7Config.tcap().dialogIdRangeEnd();
        } else {
            start = config.dialogIdRangeStart();
            end = config.dialogIdRangeEnd();
        }
        if (start > 0 && end > start) {
            LOG.info("[ra-jss7] TCAP OTID range configured: [{}, {}] — keep non-overlapping across RA nodes",
                    start, end);
        } else if (clusterManager != null && clusterManager.isClusterMode()) {
            LOG.warn("[ra-jss7] cluster mode without OTID range partition "
                    + "(dialogIdRangeStart/End unset or 0) — multi-RA same PC may collide OTIDs; "
                    + "see docs/adr/0001-ss7-ra-nn-tcap-failover.md");
        }
    }

    // ── inbound: jSS7 → SLEE (Ss7EventPublisher) ─────────────
    @Override
    public void publish(String dialogId, SleeEvent event) {
        if (!active.get() || bootstrap == null) {
            LOG.warn("RA not active — dropping {} on {}", event.getClass().getSimpleName(), dialogId);
            return;
        }
        boolean created = !sessions.containsKey(dialogId);
        MutableSession s = sessions.computeIfAbsent(dialogId,
                id -> new MutableSession(id, bootstrap.createActivityHandle(id)));
        s.touch();
        trackInbound(dialogId, event, created);
        bootstrap.fireEvent(event, s.activityHandle, null);
        LOG.debug("Fired {} on dialog={}", event.getClass().getSimpleName(), dialogId);

        if (event instanceof Ss7Event.TcapEnd || event instanceof Ss7Event.TcapAbort) {
            forceEndSession(dialogId, sessions.get(dialogId));
        }
    }

    private void trackInbound(String dialogId, SleeEvent event, boolean sessionCreated) {
        Ss7DialogOwnershipTracker tracker = ownershipTracker;
        if (tracker == null) {
            return;
        }
        if (event instanceof Ss7Event.TcapBegin || sessionCreated) {
            tracker.onDialogOpened(dialogId, parseOtid(dialogId), null, 0, 0, stateOf(event), null);
            exportSnapshotBestEffort(dialogId);
        } else if (event instanceof Ss7Event.TcapContinue) {
            tracker.onDialogTouched(dialogId, "Active", null, 0, 0);
            exportSnapshotBestEffort(dialogId);
        } else if (event instanceof Ss7Event.TcapEnd || event instanceof Ss7Event.TcapAbort) {
            // closed in forceEndSession
        } else {
            tracker.onDialogTouched(dialogId, "Active", null, 0, 0);
        }
    }

    private void exportSnapshotBestEffort(String dialogId) {
        TcapDialogFailoverPort port = failoverPort;
        if (port == null || dialogId == null) {
            return;
        }
        long otid = parseOtid(dialogId);
        if (otid <= 0) {
            return;
        }
        try {
            port.exportAndStore(otid);
        } catch (RuntimeException e) {
            LOG.debug("[ra-jss7] exportAndStore({}) failed: {}", otid, e.toString());
        }
    }

    private static String stateOf(SleeEvent event) {
        if (event instanceof Ss7Event.TcapBegin) {
            return "Active";
        }
        if (event instanceof Ss7Event.TcapEnd) {
            return "End";
        }
        if (event instanceof Ss7Event.TcapAbort) {
            return "Abort";
        }
        return "Active";
    }

    /** Back-compat convenience for the generic TCAP path. */
    public void fireEventOnDialog(String dialogId, Ss7Event event) {
        publish(dialogId, event);
    }

    // ── outbound: SBB → jSS7 ─────────────────────────────────
    public void sendOutbound(Ss7Command cmd) {
        if (!active.get()) {
            LOG.warn("RA not active — dropping {}", cmd.getClass().getSimpleName());
            return;
        }
        StickyRaCommandRouter router = stickyRouter;
        if (router == null) {
            sendOutboundLocal(cmd);
            return;
        }
        StickyRaCommandRouter.Decision decision = router.decide(cmd, isM3uaRouteReady());
        switch (decision.action()) {
            case REJECT -> {
                failoverMetrics.stickyReject();
                if (decision.reason() != null && decision.reason().startsWith("no dialog owner")) {
                    failoverMetrics.stickyMiss();
                }
                LOG.warn("[ra-jss7] sticky REJECT {}: {}",
                        cmd.getClass().getSimpleName(), decision.reason());
            }
            case FORWARD_REMOTE -> {
                IspnStickyCommandBus bus = stickyBus;
                if (bus == null || decision.owner() == null) {
                    failoverMetrics.stickyReject();
                    LOG.warn("[ra-jss7] sticky FORWARD unavailable (no bus/owner): {}",
                            decision.reason());
                    return;
                }
                bus.forward(decision.owner().ownerNodeId(), cmd);
            }
            case SEND_LOCAL -> sendOutboundLocal(cmd);
        }
    }

    /**
     * Execute on this node after sticky routing (or from sticky bus consumer).
     * Bypasses the router to avoid forward loops.
     */
    void sendOutboundLocal(Ss7Command cmd) {
        if (!active.get()) {
            LOG.warn("RA not active — dropping local {}", cmd.getClass().getSimpleName());
            return;
        }
        for (Ss7ProtocolAdapter a : adapters) {
            if (a.sendOutbound(cmd)) {
                touch(cmd.dialogId());
                afterLocalOutbound(cmd);
                return;
            }
        }
        switch (cmd) {
            case Ss7Command.TcapBegin b    -> { logCmd("BEGIN", b); afterLocalOutbound(b); }
            case Ss7Command.TcapContinue c -> { logCmd("CONTINUE", c); afterLocalOutbound(c); }
            case Ss7Command.TcapEnd e      -> {
                logCmd("END", e);
                forceEndSession(e.dialogId(), sessions.get(e.dialogId()));
            }
            case Ss7Command.TcapAbort a    -> {
                logCmd("ABORT", a);
                forceEndSession(a.dialogId(), sessions.get(a.dialogId()));
            }
            case Ss7Command.TcapUni u      -> { logCmd("UNI", u); afterLocalOutbound(u); }
            case Ss7Command.MapSendRoutingInfoForSm sri ->
                    LOG.warn("MAP SRI not handled by any adapter: {}", sri.dialogId());
            case Ss7Command.MapMtForwardSm mt ->
                    LOG.warn("MAP MT not handled by any adapter: {}", mt.dialogId());
            case Ss7Command.MapProcessUnstructuredSsResponse ussdRsp ->
                    LOG.warn("MAP USSD MO reply not handled by any adapter: {}", ussdRsp.dialogId());
            case Ss7Command.MapUnstructuredSsRequest ussdNi ->
                    LOG.warn("MAP USSD NI not handled by any adapter: {}", ussdNi.dialogId());
            case Ss7Command.MapDialogAbort abort ->
                    LOG.warn("MAP dialog abort not handled by any adapter: {}", abort.dialogId());
        }
        touch(cmd.dialogId());
    }

    private void afterLocalOutbound(Ss7Command cmd) {
        Ss7DialogOwnershipTracker tracker = ownershipTracker;
        if (tracker == null) {
            return;
        }
        if (StickyRaCommandRouter.isDialogCreating(cmd)) {
            tracker.onDialogOpened(cmd.dialogId(), parseOtid(cmd.dialogId()), null,
                    cmd.targetAddress() != null ? cmd.targetAddress().pointCode() : 0,
                    cmd.targetAddress() != null ? cmd.targetAddress().subSystemNumber() : 0,
                    "Active", cmd.dialogId());
            exportSnapshotBestEffort(cmd.dialogId());
        } else if (cmd instanceof Ss7Command.TcapContinue) {
            tracker.onDialogTouched(cmd.dialogId(), "Active", null, 0, 0);
            exportSnapshotBestEffort(cmd.dialogId());
        }
    }

    // ── session management ────────────────────────────────────
    public void forceEndSession(String did, MutableSession s) {
        if (s == null && did == null) {
            return;
        }
        if (did != null) {
            sessions.remove(did);
            Ss7DialogOwnershipTracker tracker = ownershipTracker;
            if (tracker != null) {
                tracker.onDialogClosed(did);
            }
        }
        if (s != null) {
            endActivity(s);
        }
        LOG.debug("Ended session {}", did);
    }

    private void endActivity(MutableSession s) {
        if (bootstrap != null && s.activityHandle != null) {
            bootstrap.endActivity(s.activityHandle);
        }
    }

    private void touch(String did) {
        MutableSession s = sessions.get(did);
        if (s != null) s.touch();
    }

    private void sweepIdle() {
        long cutoff = System.currentTimeMillis() - (idleTimeoutSeconds * 1000L);
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().lastActivity < cutoff) {
                forceEndSession(e.getKey(), e.getValue());
                return true;
            }
            return false;
        });
    }

    private void logCmd(String label, Ss7Command cmd) {
        LOG.info("TCAP {}: did={} target={}", label, cmd.dialogId(), cmd.targetAddress());
    }

    private static long parseOtid(String dialogId) {
        try {
            return Long.parseLong(dialogId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override public void close() { raInactive(); }

    // ── idle sweeper (Java 21+ virtual-thread scheduler) ──────
    private final class IdleSweeper {
        private volatile Thread thread;
        private volatile boolean running;

        void start(int idleSeconds) {
            running = true;
            long periodMs = Math.max(1, idleSeconds / 2) * 1000L;
            thread = Thread.ofVirtual().name("ra-jss7-sweeper").start(() -> {
                while (running) {
                    try {
                        Thread.sleep(periodMs);
                        sweepIdle();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException e) {
                        LOG.warn("idle sweep failed", e);
                    }
                }
            });
        }

        void stop() {
            running = false;
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }

    // ── per-dialog session ────────────────────────────────────
    public static final class MutableSession {
        final String sessionId;
        final ActivityHandle activityHandle;
        final long createdAt;
        volatile long lastActivity;
        MutableSession(String sid, ActivityHandle h) {
            this.sessionId = sid; this.activityHandle = h;
            this.createdAt = System.currentTimeMillis(); this.lastActivity = this.createdAt;
        }
        void touch() { this.lastActivity = System.currentTimeMillis(); }
    }
}
