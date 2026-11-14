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

import java.io.Serializable;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;

/**
 * ADR 0007 D2 — cross-node <b>inbound</b> event routing.
 *
 * <h2>Why this exists</h2>
 * {@link RaStickyCommandBus} forwards commands <em>out</em> to the node that owns
 * a live connection. The reverse direction had no implementation, and that is
 * exactly the gap the active/active scenario falls into:
 *
 * <pre>
 *   HTTP in on node-1  ──▶ SBB ──▶ MAP dialog owned by node-2 ──▶ STP
 *                                          │ CONTINUE arrives here
 *                                          ▼
 *                            node-2 acnf.lookup("gmlc-x") ──▶ MISS
 *                            (the binding lives in node-1's heap)
 * </pre>
 *
 * <p>
 * When the receiving node finds no local activity binding but the cluster knows
 * a remote owner, it publishes a {@link StickyEventEnvelope} to the owner. The
 * owner's listener re-injects the event through its <b>local</b> bootstrap port,
 * where {@code acnf.lookup(activityId)} now hits a real binding and the SBB
 * executes on the node holding the client's connection.
 *
 * <h2>Why the default differs from the command side</h2>
 * {@code jainslee.ra.sticky.forward} (commands) stays {@code false}: forwarding a
 * <em>request</em> to a node that does not own the peer connection is a protocol
 * change, and the sync-path REJECT is the honest answer.
 *
 * <p>
 * {@code jainslee.ra.sticky.forward.events} (events) defaults {@code true}:
 * forwarding a <em>response</em> back to the node that already accepted the
 * client's connection is routing, not protocol change. The socket exists on
 * exactly one heap and this is the only way to reach it.
 *
 * <h2>Transport</h2>
 * Cache {@code ra-{name}-event}, {@code DIST_SYNC}. The envelope is removed by
 * the consuming node after local delivery, exactly like the command bus — it is
 * a mailbox, not a queue.
 */
@Listener(clustered = true, observation = Listener.Observation.POST)
public final class RaStickyEventBus {

    private static final Logger LOG = LogManager.getLogger(RaStickyEventBus.class);

    /**
     * Enables inbound cross-node event routing. Deliberately independent of
     * {@link RaHaSupport#PROP_STICKY_FORWARD} so the two directions can be rolled
     * out (and rolled back) separately.
     */
    public static final String PROP_STICKY_FORWARD_EVENTS = "jainslee.ra.sticky.forward.events";

    /** Envelope older than this is dropped — its activity has moved or expired. */
    public static final long DEFAULT_MAX_AGE_MS = 120_000L;

    public static String cacheNameFor(String raName) {
        return "ra-" + raName + "-event";
    }

    private final String localNodeId;
    private final String raName;
    private final Cache<String, StickyEventEnvelope> cache;
    private final BiConsumer<String, Serializable> localExecutor;
    private final RaHaMetrics metrics;
    private final long maxAgeMs;
    private volatile boolean started;

    /**
     * Bounded single-threaded mailbox — delivery must never run on the JGroups
     * thread that invoked the cache listener. {@code AbortPolicy} so saturation
     * DROPS (and counts) instead of running on the caller.
     */
    private volatile java.util.concurrent.ThreadPoolExecutor mailboxExecutor;
    private final java.util.concurrent.atomic.LongAdder dropped =
            new java.util.concurrent.atomic.LongAdder();

    /** Bounded mailbox depth. */
    static final int MAILBOX_CAPACITY = 1024;

    public RaStickyEventBus(String localNodeId, String raName, ClusterManager clusterManager,
                             BiConsumer<String, Serializable> localExecutor, RaHaMetrics metrics) {
        this(localNodeId, raName, clusterManager, localExecutor, metrics, DEFAULT_MAX_AGE_MS);
    }

    public RaStickyEventBus(String localNodeId, String raName, ClusterManager clusterManager,
                            BiConsumer<String, Serializable> localExecutor, RaHaMetrics metrics,
                            long maxAgeMs) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.raName = Objects.requireNonNull(raName, "raName");
        Objects.requireNonNull(clusterManager, "clusterManager");
        this.localExecutor = Objects.requireNonNull(localExecutor, "localExecutor");
        this.metrics = metrics == null ? new RaHaMetrics() : metrics;
        this.maxAgeMs = maxAgeMs;
        this.cache = clusterManager.getCache(cacheNameFor(raName), CacheMode.DIST_SYNC);
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        this.mailboxExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(MAILBOX_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "ra-sticky-event-" + raName + "-" + localNodeId);
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        cache.addListener(this);
        started = true;
        LOG.info("[{}] sticky EVENT bus started node={} (cache={}, mailbox={})",
                raName, localNodeId, cache.getName(), MAILBOX_CAPACITY);
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        cache.removeListener(this);
        started = false;
        java.util.concurrent.ThreadPoolExecutor ex = this.mailboxExecutor;
        this.mailboxExecutor = null;
        if (ex != null) {
            ex.shutdown();
            try {
                if (!ex.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    ex.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.shutdownNow();
            }
        }
        LOG.info("[{}] sticky EVENT bus stopped node={}", raName, localNodeId);
    }

    /**
     * Route an inbound protocol event to the node that owns the activity.
     *
     * @param targetNodeId node holding the client connection / activity binding
     * @param activityId   activity-context name (the correlation key)
     * @param eventType    simple class name of the original {@code SleeEvent}
     * @param payload      portable, allow-list-clean POJO — never a stack type
     * @return {@code true} when the envelope was published
     */
    public boolean forward(String targetNodeId, String activityId, String eventType, Serializable payload) {
        if (targetNodeId == null || targetNodeId.equals(localNodeId)) {
            return false;
        }
        StickyEventEnvelope env = StickyEventEnvelope.of(targetNodeId, localNodeId, activityId,
                raName, eventType, payload);
        cache.put(env.envelopeId(), env);
        metrics.stickyForward();
        LOG.debug("[{}] sticky EVENT forward activity={} type={} → node={}",
                raName, activityId, eventType, targetNodeId);
        return true;
    }

    @CacheEntryCreated
    public void onCreated(CacheEntryCreatedEvent<String, StickyEventEnvelope> event) {
        if (event.isPre()) {
            return;
        }
        StickyEventEnvelope env = event.getValue();
        if (env == null || !localNodeId.equals(env.targetNodeId())) {
            return;
        }
        // ADR 0007 P6 — this runs INSIDE the Infinispan command on a JGroups
        // thread. Delivering to the SBB can block for the whole delivery latch,
        // and `cache.remove()` from inside a listener is a nested cache operation
        // that times out. Both were observed failing in the 2-node lab run.
        // Hand off to a bounded single-threaded mailbox and return immediately.
        String key = event.getKey();
        java.util.concurrent.ThreadPoolExecutor ex = this.mailboxExecutor;
        if (ex == null) {
            return;
        }
        try {
            ex.execute(() -> deliverAndAck(key, env));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            dropped.increment();
            LOG.error("[{}] sticky event mailbox full — dropping activity={} type={} from node={}",
                    raName, env.activityId(), env.eventType(), env.sourceNodeId());
        }
    }

    /** Runs on the mailbox thread, never on a JGroups thread. */
    private void deliverAndAck(String key, StickyEventEnvelope env) {
        try {
            if (env.isStale(System.currentTimeMillis(), maxAgeMs)) {
                // The activity moved or expired while this was in flight. Delivering
                // it now would inject a response into an unrelated session.
                LOG.warn("[{}] dropping stale sticky event activity={} type={} from node={} (age={}ms)",
                        raName, env.activityId(), env.eventType(), env.sourceNodeId(),
                        System.currentTimeMillis() - env.createdAtEpochMs());
                return;
            }
            localExecutor.accept(env.activityId(), env.payload());
        } catch (RuntimeException e) {
            LOG.warn("[{}] sticky event delivery failed activity={} type={}: {}",
                    raName, env.activityId(), env.eventType(), e.toString(), e);
        } finally {
            try {
                cache.remove(key);
            } catch (RuntimeException e) {
                LOG.warn("[{}] sticky event ack (remove) failed for {}: {}", raName, key, e.toString());
            }
        }
    }

    /** Envelopes dropped because the mailbox was saturated. */
    public long droppedCount() {
        return dropped.sum();
    }
}
