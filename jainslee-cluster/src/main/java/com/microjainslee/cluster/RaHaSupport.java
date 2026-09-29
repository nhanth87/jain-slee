/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bundle for RA sticky HA (ADR 0002 / Gate A): ownership, meta, sticky bus,
 * checkpoint bridge. Construct at {@code raActive} when {@link ClusterManager}
 * is bound; stop at {@code raInactive}.
 */
public final class RaHaSupport {

    private static final Logger LOG = LogManager.getLogger(RaHaSupport.class);

    /**
     * When {@code false} (default): remote owner → {@link RaStickyRouter.Action#REJECT}
     * so the SBB/session must hit the RA that owns the live connection (sync path).
     * Set {@code -Djainslee.ra.sticky.forward=true} to enable ISPN sticky-bus hop.
     */
    public static final String PROP_STICKY_FORWARD = "jainslee.ra.sticky.forward";

    /**
     * ADR 0007 D2 — inbound cross-node <b>event</b> routing. Defaults
     * {@code true}, unlike the command-side flag above, and the asymmetry is
     * deliberate:
     *
     * <ul>
     * <li>Forwarding a <b>command/request</b> to a node that does not own the
     * peer connection is a protocol change — the sync-path REJECT is honest.</li>
     * <li>Forwarding a <b>response</b> to the node that already accepted the
     * client's TCP connection is pure routing. The socket exists on exactly one
     * heap; this is the only way to write to it. Without it the canonical
     * HTTP-in-on-one-node / MAP-out-on-another scenario silently drops every
     * response.</li>
     * </ul>
     */
    public static final String PROP_STICKY_FORWARD_EVENTS = RaStickyEventBus.PROP_STICKY_FORWARD_EVENTS;

    private final String raName;
    private final String localNodeId;
    private final RaHaMetrics metrics;
    private final RaOwnershipTracker tracker;
    private final RaStickyRouter router;
    private final RaCheckpointBridge checkpointBridge;
    private final RaActivityOwnerCaches caches; // nullable
    private final ClusterManager clusterManager; // nullable — needed for the event bus
    private final boolean stickyForwardEnabled;
    private final boolean stickyEventForwardEnabled;
    private RaStickyCommandBus stickyBus; // nullable
    private RaStickyEventBus stickyEventBus; // nullable

    private RaHaSupport(
            String raName,
            String localNodeId,
            RaActivityOwnerCaches caches,
            ClusterManager clusterManager,
            RaHaMetrics metrics) {
        this.raName = Objects.requireNonNull(raName, "raName");
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.metrics = metrics == null ? new RaHaMetrics() : metrics;
        this.caches = caches;
        this.clusterManager = clusterManager;
        this.tracker = new RaOwnershipTracker(localNodeId, raName, caches, this.metrics);
        this.router = new RaStickyRouter(tracker);
        this.checkpointBridge = new RaCheckpointBridge(this.metrics);
        this.stickyForwardEnabled = Boolean.parseBoolean(
                System.getProperty(PROP_STICKY_FORWARD, "false"));
        // ADR 0007 D2 — inbound event routing defaults ON (see PROP_STICKY_FORWARD_EVENTS).
        this.stickyEventForwardEnabled = Boolean.parseBoolean(
                System.getProperty(PROP_STICKY_FORWARD_EVENTS, "true"));
    }

    public static RaHaSupport localOnly(String raName, String localNodeId) {
        return new RaHaSupport(raName, localNodeId, null, null, new RaHaMetrics());
    }

    public static RaHaSupport create(ClusterManager clusterManager, String raName) {
        Objects.requireNonNull(clusterManager, "clusterManager");
        String nodeId = clusterManager.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = "local-" + raName;
        }
        RaActivityOwnerCaches caches = RaActivityOwnerCaches.ensureCaches(clusterManager, raName);
        return new RaHaSupport(raName, nodeId, caches, clusterManager, new RaHaMetrics());
    }

    public String raName() {
        return raName;
    }

    public String localNodeId() {
        return localNodeId;
    }

    public RaHaMetrics metrics() {
        return metrics;
    }

    public RaOwnershipTracker tracker() {
        return tracker;
    }

    public RaStickyRouter router() {
        return router;
    }

    public RaCheckpointBridge checkpointBridge() {
        return checkpointBridge;
    }

    public boolean isClustered() {
        return caches != null;
    }

    public void startStickyBus(Consumer<RaStickyCommandEnvelope> localExecutor) {
        if (caches == null) {
            return;
        }
        stopStickyBus();
        stickyBus = new RaStickyCommandBus(localNodeId, caches, localExecutor, metrics);
        stickyBus.start();
    }

    public void stopStickyBus() {
        RaStickyCommandBus bus = stickyBus;
        stickyBus = null;
        if (bus != null) {
            try {
                bus.stop();
            } catch (RuntimeException e) {
                LOG.warn("[{}] sticky bus stop failed: {}", raName, e.toString());
            }
        }
        stopStickyEventBus();
        tracker.clearAll();
    }

    public void onOpened(String activityId, String status, Map<String, String> attrs) {
        tracker.onOpened(activityId, status, attrs);
    }

    public void onTouched(String activityId, String status, Map<String, String> attrs) {
        tracker.onTouched(activityId, status, attrs);
    }

    public void onClosed(String activityId) {
        tracker.onClosed(activityId);
    }

    /**
     * Sync-path default: if owner is remote and sticky forward is off, rewrite
     * {@link RaStickyRouter.Action#FORWARD_REMOTE} → {@link RaStickyRouter.Action#REJECT}
     * so callers must use the RA/node that opened the connection.
     */
    public RaStickyRouter.Decision decide(
            String activityId, boolean activityCreating, boolean routeReady) {
        RaStickyRouter.Decision d = router.decide(activityId, activityCreating, routeReady);
        if (d.action() == RaStickyRouter.Action.FORWARD_REMOTE && !stickyForwardEnabled) {
            metrics.stickyReject();
            return new RaStickyRouter.Decision(
                    RaStickyRouter.Action.REJECT,
                    d.owner(),
                    "sync-path default: owner is remote node="
                            + (d.owner() == null ? "?" : d.owner().ownerNodeId())
                            + " — SBB must send via owning RA (set "
                            + PROP_STICKY_FORWARD + "=true to allow sticky-bus hop)");
        }
        if (d.action() == RaStickyRouter.Action.REJECT) {
            metrics.stickyReject();
        }
        return d;
    }

    public boolean stickyForwardEnabled() {
        return stickyForwardEnabled;
    }

    // ── ADR 0007 D2 — inbound cross-node event routing ─────────────────────

    /**
     * Start the inbound event bus. The executor receives
     * {@code (activityId, payload)} and MUST re-inject through the RA's
     * <b>local</b> {@code RaBootstrapPort.fireEvent} path so the local ACNF
     * resolves the activity and the SBB runs on the node holding the client
     * connection.
     *
     * @param localExecutor delivers {@code (activityId, payload)} locally
     */
    public void startStickyEventBus(BiConsumer<String, Serializable> localExecutor) {
        if (clusterManager == null) {
            return;
        }
        stopStickyEventBus();
        RaStickyEventBus bus = new RaStickyEventBus(localNodeId, raName, clusterManager,
                localExecutor, metrics);
        bus.start();
        this.stickyEventBus = bus;
    }

    public void stopStickyEventBus() {
        RaStickyEventBus bus = this.stickyEventBus;
        this.stickyEventBus = null;
        if (bus != null) {
            try {
                bus.stop();
            } catch (RuntimeException e) {
                LOG.warn("[{}] sticky event bus stop failed: {}", raName, e.toString());
            }
        }
    }

    /**
     * Forward an inbound protocol response to the node that owns the activity.
     * Returns {@code false} when event forwarding is disabled, when the target
     * is this node (nothing to do), or when no bus is running.
     *
     * @param targetNodeId node holding the client connection
     * @param activityId   activity-context name
     * @param eventType    original event class name, for logging
     * @param payload      portable, allow-list-clean POJO
     */
    public boolean forwardEvent(String targetNodeId, String activityId, String eventType,
                                Serializable payload) {
        if (!stickyEventForwardEnabled) {
            metrics.stickyReject();
            LOG.warn("[{}] sticky EVENT forward disabled activity={} ({}={})", raName, activityId,
                    PROP_STICKY_FORWARD_EVENTS, false);
            return false;
        }
        RaStickyEventBus bus = this.stickyEventBus;
        if (bus == null) {
            metrics.stickyReject();
            LOG.warn("[{}] sticky event bus not started — cannot route activity={} to node={}",
                    raName, activityId, targetNodeId);
            return false;
        }
        return bus.forward(targetNodeId, activityId, eventType, payload);
    }

    public boolean stickyEventForwardEnabled() {
        return stickyEventForwardEnabled;
    }

    /** Read-only: is this node the connection owner for {@code activityId}? */
    public boolean isLocalOwner(String activityId) {
        return tracker.lookupOwner(activityId)
                .map(o -> localNodeId.equals(o.ownerNodeId()))
                .orElse(false);
    }

    public boolean forward(String targetNodeId, String activityId, Serializable payload) {
        if (!stickyForwardEnabled) {
            metrics.stickyReject();
            LOG.warn("[{}] sticky forward disabled (sync-path default) activity={}",
                    raName, activityId);
            return false;
        }
        RaStickyCommandBus bus = stickyBus;
        if (bus == null) {
            metrics.stickyReject();
            return false;
        }
        bus.forward(targetNodeId, activityId, payload);
        return true;
    }

    /** Gate A — RA checkpoint for attached SBB id. */
    public boolean checkpointSbb(String sbbId) {
        return checkpointBridge.checkpoint(sbbId);
    }

    public Optional<RaDialogOwner> lookupOwner(String activityId) {
        return tracker.lookupOwner(activityId);
    }

    public Optional<RaSessionMeta> lookupMeta(String activityId) {
        return tracker.lookupMeta(activityId);
    }
}
