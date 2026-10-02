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
import com.microjainslee.cluster.RaCheckpointBridge;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.ra.jss7.cluster.IspnStickyCommandBus;
import com.microjainslee.ra.jss7.cluster.Jss7TcapDialogFailoverPort;
import com.microjainslee.ra.jss7.cluster.MapDialogRehydrator;
import com.microjainslee.ra.jss7.cluster.SctpEndpointFailoverCoordinator;
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
import com.microjainslee.ra.jss7.event.Ss7MapEvent;
import com.microjainslee.cluster.RaDialogOwner;
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
    /**
     * Nextgen STP transit-plane profile (canRelay / removeSpc / incoming ACL /
     * HA mode marker). Null = terminating-end-node behavior, zero change.
     */
    private volatile StpTransitProfile stpTransitProfile;
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
    /** ADR 0007 D2 — HA bundle; also owns the inbound sticky-EVENT bus. */
    private volatile com.microjainslee.cluster.RaHaSupport haSupport;
    private volatile TcapDialogFailoverPort failoverPort;
    private volatile SctpEndpointFailoverCoordinator endpointCoordinator;
    /** ADR 0007 D11 — forwards non-owned DTIDs to their owner; null when unclustered. */
    private volatile com.microjainslee.ra.jss7.cluster.Ss7InboundDialogRouter inboundRouter;
    /** ADR 0007 D3/D12 — activity claims + per-dialog leases (the transmit fence); null when unclustered. */
    private volatile com.microjainslee.cluster.RaDialogLeaseCaches leases;
    /** dialogId → OTID lease key claimed by this node; the heartbeat's liveness source. */
    private final Map<String, String> dialogLeaseKeys = new ConcurrentHashMap<>();
    /** The values of {@link #dialogLeaseKeys}, as a set: O(1) liveness per lease. */
    private final java.util.Set<String> liveDialogLeaseKeys = ConcurrentHashMap.newKeySet();
    private final TcapFailoverMetrics failoverMetrics = new TcapFailoverMetrics();
    /** Gate A — RA-only SBB checkpoint (SBBs must not call this). */
    private final RaCheckpointBridge checkpointBridge = new RaCheckpointBridge();

    // ── configuration ────────────────────────────────────────
    public void setBootstrapPort(RaBootstrapPort bp) { this.bootstrap = bp; }
    public RaBootstrapPort bootstrap() { return bootstrap; }
    public void setConfig(Ss7RaConfig cfg) { this.config = cfg; this.ss7Config = null; }
    public Ss7RaConfig config() { return config; }
    /** Full jSS7-25 JSON model — multi SCTP links / multi AS. Clears flat override. */
    public void setSs7Config(Ss7Config cfg) { this.ss7Config = cfg; }
    public Ss7Config ss7Config() { return ss7Config; }
    /**
     * Nextgen STP transit-plane profile. Call before {@link #raActive()}.
     * Null (default) = no transit behavior — the stack stays a terminating node.
     */
    public void setStpTransitProfile(StpTransitProfile profile) { this.stpTransitProfile = profile; }
    public StpTransitProfile stpTransitProfile() { return stpTransitProfile; }
    /** True when this RA runs as an STP transit node (canRelay applied). */
    public boolean isStpTransitMode() {
        StpTransitProfile p = stpTransitProfile;
        return p != null && p.transitEnabled();
    }
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

    /** Gate A — bind MicroSleeContainer so RA can checkpoint SBB CMP/profile. */
    public void setMicroSleeContainer(Object container) {
        checkpointBridge.bindContainer(container);
    }

    public RaCheckpointBridge checkpointBridge() {
        return checkpointBridge;
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

    public boolean isSctpAssociationUp() {
        Ss7Stack s = stack;
        return active.get() && s != null && s.isSctpAssociationUp();
    }

    public boolean isM3uaAsActive() {
        Ss7Stack s = stack;
        return active.get() && s != null && s.isM3uaAsActive();
    }

    /**
     * Per-link / per-AS truth for admin status. A multi-link host cannot be summarised
     * by "any association up" — that hid the dead link on 2026-10-01 for 16 hours.
     * Empty when the RA is not active.
     */
    public java.util.List<java.util.Map<String, Object>> ss7AssociationDetails() {
        Ss7Stack s = stack;
        return active.get() && s != null ? s.associationDetails() : java.util.List.of();
    }

    public java.util.List<java.util.Map<String, Object>> ss7ApplicationServerDetails() {
        Ss7Stack s = stack;
        return active.get() && s != null ? s.applicationServerDetails() : java.util.List.of();
    }

    /** Shared SCTP/M3UA AIMD controller. Disabled when the stack is down. */
    public org.mobicents.protocols.sctp.spi.AdaptiveSendController congestionController() {
        Ss7Stack s = stack;
        return s == null
                ? org.mobicents.protocols.sctp.spi.AdaptiveSendController.disabled()
                : s.congestionController();
    }

    // ── lifecycle ────────────────────────────────────────────
    public void raActive() {
        if (!active.compareAndSet(false, true)) return;
        try {
            this.stack = ss7Config != null ? new Ss7Stack(ss7Config) : new Ss7Stack(config);
            this.stack.start();
            applyStpTransitProfile(this.stack, this.stpTransitProfile);
            if (ss7Config == null) {
                // Flat-config path: DESIGN §10.2/P4 guardrail — override traffic mode
                // under an active-active fabric gets a WARN (full-JSON path is
                // operator-authored and left untouched).
                config.warnIfOverrideTrafficMode(stpTransitProfile == null
                        ? StpTransitProfile.HaMode.ACTIVE_ACTIVE
                        : stpTransitProfile.haMode());
            }
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
            // Mirror raInactive for partial start: detach, clear resolver, stop stack.
            rollbackPartialActivation();
            LOG.error("jSS7 RA activation failed", e);
            throw new IllegalStateException("jSS7 RA activation failed", e);
        }
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) return;
        sweeper.stop();
        detachAdapters();
        clearMissingDialogResolver();
        stopAndClearStack();
        sessions.values().forEach(this::endActivity);
        sessions.clear();
        if (ownershipTracker != null) {
            ownershipTracker.clearAll();
        }
        teardownOwnership();
        LOG.info("jSS7 RA deactivated");
    }

    /**
     * Undo a mid-{@link #raActive()} failure after {@code active} has been cleared.
     * Same detach / resolver / stack / ownership cleanup as {@link #raInactive()},
     * without session end (sessions were never published).
     */
    void rollbackPartialActivation() {
        sweeper.stop();
        detachAdapters();
        clearMissingDialogResolver();
        stopAndClearStack();
        teardownOwnership();
    }

    private void detachAdapters() {
        for (Ss7ProtocolAdapter a : adapters) {
            try {
                a.detach();
            } catch (RuntimeException e) {
                LOG.warn("detach {} failed", a.protocol(), e);
            }
        }
        adapters.clear();
    }

    private void stopAndClearStack() {
        Ss7Stack s = stack;
        stack = null;
        if (s != null) {
            try {
                s.stop();
            } catch (RuntimeException e) {
                LOG.warn("stack stop failed during RA teardown: {}", e.toString());
            }
        }
    }

    /**
     * Apply the Nextgen STP transit-plane profile to a RUNNING jSS7 stack.
     * Package-private static for testability; fail-fast — a misconfigured STP
     * must never silently relay without ACL/canRelay applied (throws).
     *
     * @param s       started transport stack (SCCP RUNNING), never null
     * @param profile nullable — null or {@code !transitEnabled} → no-op
     */
    static void applyStpTransitProfile(Ss7Stack s, StpTransitProfile profile) throws Exception {
        if (profile == null) {
            return;
        }
        profile.validate();
        org.restcomm.protocols.ss7.config.Ss7Stack under = s.underlying();
        org.restcomm.protocols.ss7.sccp.impl.SccpStackImpl sccp =
                under == null ? null : under.sccpStack();
        if (sccp == null) {
            throw new IllegalStateException("STP transit profile requires a started SCCP stack");
        }
        if (profile.transitEnabled()) {
            sccp.setCanRelay(true);
        }
        sccp.setRemoveSpc(profile.removeSpcOnRelay());

        org.restcomm.protocols.ss7.sccp.impl.acl.SccpIncomingAcl acl = sccp.getSccpIncomingAcl();
        if (acl != null) {
            acl.importState(profile.toIncomingAclState());
        } else if (profile.aclEnabled()) {
            throw new IllegalStateException("STP transit ACL requested but stack exposes no ACL (jSS7 too old?)");
        }
        LOG.info("[ra-jss7] STP transit profile applied: transit={} removeSpc={} haMode={} aclEnabled={} rules={} maskGtLogs={}",
                profile.transitEnabled(), profile.removeSpcOnRelay(), profile.haMode(),
                profile.aclEnabled(), profile.aclRules().size(), profile.maskGtInLogs());
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
            // ADR 0007 D2 / P2 — inbound cross-node event routing (R1).
            //
            // Without this the canonical scenario drops every response: a
            // TC-CONTINUE lands on THIS node, acnf.lookup(dialogId) finds no local
            // binding (the HTTP connection and the SLEE activity live on the other
            // node's heap), and the delivery dies. The peer STP is free to send the
            // CONTINUE to whichever ASP of the AS it loadshares onto.
            startStickyEventBus(nodeId, clusterManager);
        } else {
            stickyBus = null;
        }
        wireFailoverPort(caches);
        wireEndpointCoordinator(nodeId, caches);
        wireInboundRouter();
    }

    /**
     * ADR 0007 D11 — in cluster mode every node is a full SS7 node (one ASP of the
     * shared AS) with a disjoint OTID range. A CONTINUE/END/ABORT the STP delivers
     * here for another node's dialog is forwarded to that node as raw PDU bytes.
     *
     * <p>
     * Cluster mode without a range, or with an overlapping one, fails activation:
     * routing by DTID would otherwise deliver to the wrong node, and an HA feature
     * that silently degrades must not start.
     */
    private void wireInboundRouter() {
        ClusterManager cm = clusterManager;
        Ss7Stack s = stack;
        if (cm == null || !cm.isClusterMode() || s == null) {
            return;
        }
        long[] range = otidRange();
        if (range[0] <= 0 || range[1] <= range[0]) {
            throw new IllegalStateException("[" + raName + "] cluster mode requires a TCAP OTID range "
                    + "(dialogIdRangeStart/End) disjoint from every other node — ADR 0007 D11");
        }
        com.microjainslee.ra.jss7.cluster.Ss7InboundDialogRouter router =
                new com.microjainslee.ra.jss7.cluster.Ss7InboundDialogRouter(raName, cm,
                        new com.microjainslee.cluster.ClusterUnicast(cm),
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
                        failoverMetrics);
        com.microjainslee.cluster.RaDialogLeaseCaches dialogLeases =
                com.microjainslee.cluster.RaDialogLeaseCaches.create(cm, raName);
        dialogLeases.setLocalLiveness(this::leaseStillInUse);
        this.leases = dialogLeases;
        if (failoverPort instanceof com.microjainslee.ra.jss7.cluster.Jss7TcapDialogFailoverPort port) {
            // A survivor imports a dialog only after winning its lease.
            port.setTakeoverGuard(otid -> dialogLeases.takeOver(dialogLeaseKey(otid)));
        }
        router.start(range[0], range[1]);
        this.inboundRouter = router;
    }

    /** Heartbeat liveness: renew only leases that protect a dialog / activity still on this node. */
    private boolean leaseStillInUse(String key) {
        if (key.startsWith(com.microjainslee.ra.jss7.cluster.Ss7TransmitFence.ACTIVITY_PREFIX)) {
            String dialogId = key.substring(com.microjainslee.ra.jss7.cluster.Ss7TransmitFence.ACTIVITY_PREFIX.length());
            return sessions.containsKey(dialogId) || dialogLeaseKeys.containsKey(dialogId);
        }
        return liveDialogLeaseKeys.contains(key);
    }

    /** Lease key of a TCAP dialog: its local OTID (the transaction the fence protects). */
    static String dialogLeaseKey(long otid) {
        return com.microjainslee.ra.jss7.cluster.Ss7TransmitFence.dialogLeaseKey(otid);
    }

    /** Idempotency claim of an app activity id (one BEGIN per activity, cluster-wide). */
    static String activityClaimKey(String dialogId) {
        return com.microjainslee.ra.jss7.cluster.Ss7TransmitFence.activityClaimKey(dialogId);
    }

    /**
     * ADR 0007 M — the TCAP OTID behind an RA dialog id: the id itself when it is
     * numeric (inbound dialogs), else the adapter's correlation map (outbound
     * dialogs named by the app, e.g. {@code gmlc-<uuid>}). {@code 0} when unknown.
     */
    long otidOf(String dialogId) {
        long parsed = parseOtid(dialogId);
        if (parsed > 0 || dialogId == null) {
            return parsed;
        }
        for (Ss7ProtocolAdapter a : adapters) {
            Long id = a.localDialogIdOf(dialogId);
            if (id != null && id > 0) {
                return id;
            }
        }
        return 0L;
    }

    /** @return {start, end} of this node's configured OTID range ({0, 0} when unset). */
    private long[] otidRange() {
        if (ss7Config != null && ss7Config.tcap() != null) {
            return new long[] { ss7Config.tcap().dialogIdRangeStart(), ss7Config.tcap().dialogIdRangeEnd() };
        }
        return new long[] { config.dialogIdRangeStart(), config.dialogIdRangeEnd() };
    }

    /** Test / admin access: the D11 router, or {@code null}. */
    public com.microjainslee.ra.jss7.cluster.Ss7InboundDialogRouter inboundRouter() {
        return inboundRouter;
    }

    /**
     * ADR 0007 D2 — start the inbound sticky-event bus and teach this RA to
     * re-inject a forwarded event through its own <b>local</b> bootstrap port.
     *
     * <p>
     * The executor runs on the node that OWNS the activity, so
     * {@code acnf.lookup(activityId)} hits a real binding and the SBB executes
     * where the client's connection lives. Events arriving the normal way (this
     * node owns the dialog) never touch this path.
     *
     * <p>
     * The payload must be a portable {@code com.microjainslee.*} POJO: jSS7
     * {@code MAPMessage} objects are not allow-listed and cannot be shipped.
     */
    private void startStickyEventBus(String nodeId, com.microjainslee.cluster.ClusterManager cm) {
        try {
            com.microjainslee.cluster.RaHaSupport ha =
                    com.microjainslee.cluster.RaHaSupport.create(cm, raName);
            this.haSupport = ha;
            ha.startStickyEventBus(this::deliverStickyEventLocal);
            LOG.info("[ra-jss7] sticky EVENT bus started node={} "
                    + "(inbound cross-node response routing enabled)", nodeId);
        } catch (RuntimeException e) {
            // Never fail raActive() because HA wiring failed — log loudly instead.
            LOG.error("[ra-jss7] sticky EVENT bus failed to start; cross-node responses "
                    + "will be dropped: " + e.toString(), e);
        }
    }

    /**
     * Re-inject an event forwarded from another node, on this node's local path.
     * Callers already filtered to envelopes addressed to this node.
     *
     * @param activityId activity-context name (the correlation key)
     * @param payload    portable POJO produced by the sending RA
     */
    private void deliverStickyEventLocal(String activityId, java.io.Serializable payload) {
        RaBootstrapPort bp = bootstrap;
        if (bp == null || !active.get()) {
            LOG.warn("[ra-jss7] sticky event for activity={} dropped: RA not active", activityId);
            return;
        }
        SleeEvent event = rebuildStickyEvent(activityId, payload);
        if (event == null) {
            return;
        }
        try {
            MutableSession existing = sessions.get(activityId);
            // Reuse the live handle when this node already has the dialog; otherwise
            // let the bootstrap mint one — the container binds the activity name
            // either way, which is what makes acnf.lookup() resolve locally.
            ActivityHandle handle = existing != null
                    ? existing.activityHandle
                    : bp.createActivityHandle(activityId);
            bp.fireEvent(event, handle, null);
            LOG.debug("[ra-jss7] delivered sticky event {} for activity={}",
                    event.getClass().getSimpleName(), activityId);
        } catch (RuntimeException e) {
            LOG.error("[ra-jss7] sticky event delivery failed for activity=" + activityId, e);
        }
    }

    /**
     * Turn a portable payload back into a deliverable {@link SleeEvent}.
     *
     * <p>
     * <b>Only {@code MapEventPayload} is accepted.</b> The {@link Ss7MapEvent} /
     * {@link Ss7Event} records are deliberately NOT {@code Serializable}: both
     * interfaces are sealed and no permitted subtype implements it, which the
     * compiler can prove — so no {@code SleeEvent} can ever travel the cluster
     * bus, by construction rather than by convention. That is the correct
     * outcome: {@code Ss7MapEvent.Service} carries a jSS7 {@code MAPMessage},
     * which is outside the marshalling allow-list and not stable across stack
     * versions. The receiving node therefore materialises
     * {@link Ss7MapEvent.Remote} from the portable summary instead.
     *
     * <p>
     * Returns {@code null} for an unknown payload — never throws, because a
     * malformed envelope must not kill the bus listener.
     */
    /**
     * Turn a portable payload back into a deliverable {@link SleeEvent}.
     *
     * <p>
     * <b>Only {@code MapEventPayload} is accepted.</b> The {@link Ss7MapEvent} /
     * {@link Ss7Event} records are deliberately NOT {@code Serializable}: both
     * interfaces are sealed and no permitted subtype implements it, which the
     * compiler can prove — so no {@code SleeEvent} can ever travel the cluster
     * bus, by construction rather than by convention. That is the correct
     * outcome: {@code Ss7MapEvent.Service} carries a jSS7 {@code MAPMessage},
     * which is outside the marshalling allow-list and not stable across stack
     * versions. The receiving node therefore materialises
     * {@link Ss7MapEvent.Remote} from the portable summary instead.
     *
     * <p>
     * Returns {@code null} for an unknown payload — never throws, because a
     * malformed envelope must not kill the bus listener.
     */
    private SleeEvent rebuildStickyEvent(String activityId, java.io.Serializable payload) {
        if (payload instanceof com.microjainslee.ra.jss7.cluster.MapEventPayload mapPayload) {
            return new Ss7MapEvent.Remote(activityId, mapPayload.typeName(), mapPayload);
        }
        LOG.warn("[ra-jss7] sticky event for activity={} carries unknown payload type {} — dropped",
                activityId, payload == null ? "null" : payload.getClass().getName());
        return null;
    }

    private void wireEndpointCoordinator(String nodeId, Ss7DialogClusterCaches caches) {
        if (caches == null || clusterManager == null || config == null) {
            endpointCoordinator = null;
            return;
        }
        try {
            String preferred = config.resolvedLocalEndpoint();
            List<String> endpoints = config.allLocalEndpoints();
            SctpEndpointFailoverCoordinator coord = new SctpEndpointFailoverCoordinator(
                    nodeId,
                    endpoints,
                    preferred,
                    caches,
                    clusterManager,
                    (endpointKey, generation, takeover) -> LOG.info(
                            "[ra-jss7] SCTP endpoint claimed endpoint={} generation={} takeover={}",
                            endpointKey, generation, takeover));
            coord.start();
            endpointCoordinator = coord;
        } catch (RuntimeException e) {
            LOG.warn("[ra-jss7] SCTP endpoint coordinator not started: {}", e.toString());
            endpointCoordinator = null;
        }
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
                failoverMetrics,
                new MapDialogRehydrator(() -> {
                    Ss7Stack st = stack;
                    return st != null ? st.mapProvider() : null;
                }));
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
        com.microjainslee.cluster.RaDialogLeaseCaches dialogLeases = leases;
        leases = null;
        if (dialogLeases != null) {
            dialogLeases.stop();
        }
        dialogLeaseKeys.clear();
        liveDialogLeaseKeys.clear();
        com.microjainslee.ra.jss7.cluster.Ss7InboundDialogRouter router = inboundRouter;
        inboundRouter = null;
        if (router != null) {
            try {
                router.stop();
            } catch (RuntimeException e) {
                LOG.warn("inbound dialog router stop failed: {}", e.toString());
            }
        }
        SctpEndpointFailoverCoordinator coord = endpointCoordinator;
        endpointCoordinator = null;
        if (coord != null) {
            try {
                coord.stop();
            } catch (RuntimeException e) {
                LOG.warn("endpoint coordinator stop failed: {}", e.toString());
            }
        }
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
        com.microjainslee.cluster.RaHaSupport ha = this.haSupport;
        this.haSupport = null;
        if (ha != null) {
            try {
                ha.stopStickyEventBus();
            } catch (RuntimeException e) {
                LOG.warn("sticky event bus stop failed: {}", e.toString());
            }
        }
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
        long[] range = otidRange();
        long start = range[0];
        long end = range[1];
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
        // ADR 0007 D2 / P2 — R1: this node holds the TCAP dialog but NOT the
        // client's connection. If the cluster says another node owns the activity,
        // hand the response over instead of firing into a local activity that
        // does not exist here (which used to die with
        // IllegalStateException: Unknown activity handle).
        // Superseded by D11 when the inbound router runs: a PDU only reaches
        // publish() on the node that owns the dialog, which is also where the SBB
        // and the client connection live.
        if (inboundRouter == null && tryForwardToActivityOwner(dialogId, event)) {
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

    /**
     * ADR 0007 D2 — forward an inbound response to the node owning the activity.
     *
     * <p>
     * Returns {@code true} when the event was handed to the sticky-event bus, in
     * which case the caller must NOT also fire it locally (that would deliver the
     * response twice).
     *
     * <p>
     * Only consulted when the cluster positively reports a <b>different</b> owner.
     * An unknown owner falls through to local delivery: inventing a remote hop
     * for an activity nobody claimed would strand the response.
     */
    private boolean tryForwardToActivityOwner(String dialogId, SleeEvent event) {
        com.microjainslee.cluster.RaHaSupport ha = this.haSupport;
        if (ha == null || !ha.isClustered() || !ha.stickyEventForwardEnabled()) {
            return false;
        }
        String ownerNodeId = lookupRemoteOwnerNodeId(dialogId);
        if (ownerNodeId == null) {
            return false;   // unknown / owned here — deliver locally
        }
        java.io.Serializable payload = toStickyPayload(dialogId, event);
        if (payload == null) {
            return false;   // not portable — fall back to local delivery + log
        }
        return ha.forwardEvent(ownerNodeId, dialogId, event.getClass().getSimpleName(), payload);
    }

    /**
     * @return the node owning the activity when it is known AND not this node;
     *         {@code null} when unknown or local.
     */
    private String lookupRemoteOwnerNodeId(String activityId) {
        try {
            Ss7DialogOwnershipTracker tracker = this.ownershipTracker;
            if (tracker == null) {
                return null;
            }
            String nodeId = tracker.lookupOwner(activityId).map(RaDialogOwner::ownerNodeId).orElse(null);
            if (nodeId == null || nodeId.equals(tracker.localNodeId())) {
                return null;   // unknown, or ours — deliver locally
            }
            return nodeId;
        } catch (RuntimeException e) {
            LOG.debug("[ra-jss7] owner lookup failed for {} — delivering locally", activityId, e);
            return null;
        }
    }

    /**
     * Flatten an inbound event into an allow-list-clean payload.
     * Returns {@code null} for events with no portable form.
     */
    private java.io.Serializable toStickyPayload(String dialogId, SleeEvent event) {
        if (event instanceof Ss7MapEvent.Service svc) {
            return com.microjainslee.ra.jss7.cluster.MapEventPayload.of(dialogId, svc.type(), svc.message());
        }
        if (event instanceof Ss7MapEvent.Dialog) {
            // A dialog-lifecycle notice carries no payload worth shipping; the
            // authoritative dialog state lives in the owner's TCAP snapshot.
            return com.microjainslee.ra.jss7.cluster.MapEventPayload.of(dialogId, null, null);
        }
        if (event instanceof Ss7MapEvent.Error err) {
            return com.microjainslee.ra.jss7.cluster.MapEventPayload.of(dialogId, null, null);
        }
        return null;
    }

    /** The OTID is from this node's range: the claim only fails on a partition minority. */
    private void claimDialogLease(String dialogId) {
        com.microjainslee.cluster.RaDialogLeaseCaches dialogLeases = leases;
        long otid = dialogLeases != null ? otidOf(dialogId) : 0L;
        if (otid <= 0) {
            return;
        }
        dialogLeaseKeys.put(dialogId, dialogLeaseKey(otid));
        liveDialogLeaseKeys.add(dialogLeaseKey(otid));
        if (!dialogLeases.tryClaim(dialogLeaseKey(otid))) {
            LOG.warn("[ra-jss7] dialog lease for {} (otid {}) not acquired — later sends will be fenced",
                    dialogId, otid);
        }
    }

    private void trackInbound(String dialogId, SleeEvent event, boolean sessionCreated) {
        Ss7DialogOwnershipTracker tracker = ownershipTracker;
        if (event instanceof Ss7Event.TcapBegin || sessionCreated) {
            claimDialogLease(dialogId);
            if (tracker != null) {
                tracker.onDialogOpened(dialogId, parseOtid(dialogId), null, 0, 0, stateOf(event), null);
            }
            exportSnapshotBestEffort(dialogId);
            // Gate A — RA-only SBB checkpoint (independent of sticky ownership).
            checkpointBridge.checkpoint(dialogId);
        } else if (event instanceof Ss7Event.TcapContinue cont) {
            if (tracker != null) {
                tracker.onDialogTouched(dialogId, "Active", null, 0, 0);
            }
            // Snapshot / checkpoint only on Continue-with-components (grilling Q13=C).
            if (cont.components() != null && !cont.components().isEmpty()) {
                exportSnapshotBestEffort(dialogId);
                checkpointBridge.checkpoint(dialogId);
            }
        } else if (event instanceof Ss7Event.TcapEnd || event instanceof Ss7Event.TcapAbort) {
            // closed in forceEndSession
        } else if (tracker != null) {
            tracker.onDialogTouched(dialogId, "Active", null, 0, 0);
        }
    }

    private void exportSnapshotBestEffort(String dialogId) {
        TcapDialogFailoverPort port = failoverPort;
        if (port == null || dialogId == null) {
            return;
        }
        long otid = otidOf(dialogId);          // ADR 0007 M: app-named dialogs too
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
        if (decision.action() == StickyRaCommandRouter.Action.SEND_LOCAL) {
            String fenced = fenceBeforeTransmit(cmd);
            if (fenced != null) {
                decision = new StickyRaCommandRouter.Decision(StickyRaCommandRouter.Action.REJECT,
                        decision.owner(), fenced);
            }
        }
        switch (decision.action()) {
            case REJECT -> {
                failoverMetrics.stickyReject();
                if (decision.reason() != null && decision.reason().startsWith("no dialog owner")) {
                    failoverMetrics.stickyMiss();
                }
                LOG.warn("[ra-jss7] sticky REJECT {}: {}",
                        cmd.getClass().getSimpleName(), decision.reason());
                rejectToSbb(cmd, decision.reason());
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
     * ADR 0007 D3 / D12 — the last check before a command reaches the wire.
     *
     * <ul>
     *   <li>A dialog-creating command first claims its activity id cluster-wide:
     *       a client retry of the same activity on another node is refused here
     *       instead of producing a second BEGIN towards the HLR.</li>
     *   <li>Any other command must pass the dialog lease fence: this incarnation
     *       still owns the TCAP transaction and the lease cache is available. On
     *       the minority side of a partition the cache is unavailable, so a
     *       zombie that still holds its SCTP association does not transmit.</li>
     * </ul>
     *
     * @return {@code null} to proceed, else the refusal reason
     */
    private String fenceBeforeTransmit(Ss7Command cmd) {
        com.microjainslee.cluster.RaDialogLeaseCaches dialogLeases = leases;
        return dialogLeases == null ? null
                : new com.microjainslee.ra.jss7.cluster.Ss7TransmitFence(dialogLeases, this::otidOf).check(cmd);
    }

    /**
     * ADR 0007 L — a refused command must reach the SBB. Logging alone left the
     * activity (and any parked HTTP request) waiting for its timeout.
     */
    private void rejectToSbb(Ss7Command cmd, String reason) {
        String dialogId = cmd.dialogId();
        if (dialogId == null || dialogId.isBlank() || bootstrap == null) {
            return;
        }
        try {
            publish(dialogId, new Ss7MapEvent.Dialog(dialogId, Ss7MapEvent.Kind.REJECT,
                    "local-refusal: " + reason));
        } catch (RuntimeException e) {
            LOG.warn("[ra-jss7] could not report refusal of {} to the SBB: {}", dialogId, e.toString());
        } finally {
            MutableSession s = sessions.get(dialogId);
            if (s != null) {
                forceEndSession(dialogId, s);
            }
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
            case Ss7Command.MapSendRoutingInfoForSmResponse sriRsp ->
                    LOG.warn("MAP SRI response not handled by any adapter: {}", sriRsp.dialogId());
            case Ss7Command.Ss7RestartLink restart -> {
                // Per-link recovery: replaces one SCTP socket and re-binds M3UA. Runs
                // here (on the RA's own thread, outside any dialog) and never touches
                // TCAP/SCCP/MAP or the other links.
                Ss7Stack.LinkRestart result = stack == null
                        ? new Ss7Stack.LinkRestart(restart.linkName(), false, "stack-absent", 0,
                                false, false, null)
                        : stack.restartAssociation(restart.linkName(), restart.timeoutMs());
                LOG.warn("[ra-jss7] per-link recovery requested link={} ok={} detail={}",
                        restart.linkName(), result.ok(), result.detail());
            }
            case Ss7Command.Ss7ReplaceLink replace -> {
                // Per-link recovery step two: the Association object itself is stale,
                // so swap it under the same name (ASP stopped, rebind, start). Same
                // thread rules as the bounce above: no dialog, no other link touched.
                Ss7Stack.LinkRestart result = stack == null
                        ? new Ss7Stack.LinkRestart(replace.linkName(), false, "stack-absent", 0,
                                false, false, null)
                        : stack.replaceAssociation(replace.linkName(), replace.timeoutMs());
                LOG.warn("[ra-jss7] per-link replace requested link={} ok={} detail={}",
                        replace.linkName(), result.ok(), result.detail());
            }
            case Ss7Command.MapMtForwardSm mt ->
                    LOG.warn("MAP MT not handled by any adapter: {}", mt.dialogId());
            case Ss7Command.MapMoForwardSm mo ->
                    LOG.warn("MAP MO-ForwardSM not handled by any adapter: {}", mo.dialogId());
            case Ss7Command.MapReportSMDeliveryStatus report ->
                    LOG.warn("MAP ReportSM not handled by any adapter: {}", report.dialogId());
            case Ss7Command.MapAtiRequest ati ->
                    LOG.warn("MAP ATI not handled by any adapter: {}", ati.dialogId());
            case Ss7Command.MapSendRoutingInformation sri ->
                    LOG.warn("MAP call-handling SRI not handled by any adapter: {}", sri.dialogId());
            case Ss7Command.MapProvideSubscriberInfo psi ->
                    LOG.warn("MAP PSI not handled by any adapter: {}", psi.dialogId());
            case Ss7Command.MapSendRoutingInfoForLcs sriLcs ->
                    LOG.warn("MAP SRI-LCS not handled by any adapter: {}", sriLcs.dialogId());
            case Ss7Command.MapProvideSubscriberLocation psl ->
                    LOG.warn("MAP PSL not handled by any adapter: {}", psl.dialogId());
            case Ss7Command.MapSubscriberLocationReportResponse slr ->
                    LOG.warn("MAP SLR response not handled by any adapter: {}", slr.dialogId());
            case Ss7Command.MapProcessUnstructuredSsResponse ussdRsp ->
                    LOG.warn("MAP USSD MO reply not handled by any adapter: {}", ussdRsp.dialogId());
            case Ss7Command.MapUnstructuredSsRequest ussdNi ->
                    LOG.warn("MAP USSD NI not handled by any adapter: {}", ussdNi.dialogId());
            case Ss7Command.MapUnstructuredSsContinue ussdCont ->
                    LOG.warn("MAP USSD NI continue not handled by any adapter: {}", ussdCont.dialogId());
            case Ss7Command.MapDialogClose close ->
                    LOG.warn("MAP dialog close not handled by any adapter: {}", close.dialogId());
            case Ss7Command.MapDialogAbort abort ->
                    LOG.warn("MAP dialog abort not handled by any adapter: {}", abort.dialogId());
        }
        touch(cmd.dialogId());
    }

    private void afterLocalOutbound(Ss7Command cmd) {
        Ss7DialogOwnershipTracker tracker = ownershipTracker;
        if (StickyRaCommandRouter.isDialogCreating(cmd)) {
            claimDialogLease(cmd.dialogId());
            if (tracker != null) {
                tracker.onDialogOpened(cmd.dialogId(), parseOtid(cmd.dialogId()), null,
                        cmd.targetAddress() != null ? cmd.targetAddress().pointCode() : 0,
                        cmd.targetAddress() != null ? cmd.targetAddress().subSystemNumber() : 0,
                        "Active", cmd.dialogId());
            }
            exportSnapshotBestEffort(cmd.dialogId());
            // Gate A — RA-only checkpoint on dialog create.
            checkpointBridge.checkpoint(cmd.dialogId());
        } else if (cmd instanceof Ss7Command.TcapContinue cont) {
            if (tracker != null) {
                tracker.onDialogTouched(cmd.dialogId(), "Active", null, 0, 0);
            }
            // Gate A parity with inbound: Continue-with-components only.
            if (cont.components() != null && !cont.components().isEmpty()) {
                exportSnapshotBestEffort(cmd.dialogId());
                checkpointBridge.checkpoint(cmd.dialogId());
            }
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
            com.microjainslee.cluster.RaDialogLeaseCaches dialogLeases = leases;
            String otidKey = dialogLeaseKeys.remove(did);
            if (otidKey != null) {
                liveDialogLeaseKeys.remove(otidKey);
            }
            if (dialogLeases != null) {
                if (otidKey == null) {
                    long otid = otidOf(did);
                    otidKey = otid > 0 ? dialogLeaseKey(otid) : null;
                }
                if (otidKey != null) {
                    dialogLeases.release(otidKey);
                }
                dialogLeases.release(activityClaimKey(did));
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
