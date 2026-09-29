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
 * ADR 0007 D2 — <b>container-level</b> cross-node event ingress.
 *
 * <h2>Why this is separate from the per-RA {@link RaStickyEventBus}</h2>
 * The per-RA bus is keyed to the RA that produced the event. That is fine when
 * every node runs every RA — the same RA instance that would have received the
 * response locally also listens for the forwarded one.
 *
 * <p>
 * It breaks in exactly the topology the whole ADR is about:
 *
 * <pre>
 *   node-1: ra-http-server  (client socket + SLEE activity)
 *   node-2: ra-jss7         (TCAP dialog + M3UA ASP)
 *
 *   MAP CONTINUE lands on node-2
 *     → node-2 forwards on the ra-jss7-event cache
 *     → node-1 has NO ra-jss7 instance, so NOTHING listens
 *     → the response is stranded; the client waits forever
 * </pre>
 *
 * <p>
 * A node that only exposes HTTP must still be able to receive an event produced
 * by a protocol RA it does not even run. So this ingress is owned by the
 * <b>container</b>, addressed by activity id rather than by RA, and delivers
 * through whatever local bootstrap path the embedder supplies.
 *
 * <h2>Scope</h2>
 * One shared cache ({@link #CACHE_NAME}) rather than one per RA: routing is by
 * activity, so the receiving side has no idea which RA originated it. Payloads
 * stay per-RA allow-list-clean POJOs (see {@code MapEventPayload}).
 *
 * <p>
 * Delivery must be <b>idempotent-safe</b>: an activity may be forwarded twice
 * (at-least-once mailbox semantics). SBBs that cannot tolerate a duplicate
 * response must key on the dialog id, which is unique per TCAP dialog.
 */
@Listener(clustered = true, observation = Listener.Observation.POST)
public final class ContainerStickyEventIngress {

    private static final Logger LOG = LogManager.getLogger(ContainerStickyEventIngress.class);

    /** Shared ingress cache. One per SLEE cluster, not per RA. */
    public static final String CACHE_NAME = "slee-event-ingress";

    /** Envelope older than this is dropped — its activity moved or expired. */
    public static final long DEFAULT_MAX_AGE_MS = 120_000L;

    private final String localNodeId;
    private final Cache<String, StickyEventEnvelope> cache;
    private final BiConsumer<String, Serializable> localExecutor;
    private final long maxAgeMs;
    private volatile boolean started;

    public ContainerStickyEventIngress(ClusterManager clusterManager, String localNodeId,
                                       BiConsumer<String, Serializable> localExecutor) {
        this(clusterManager, localNodeId, localExecutor, DEFAULT_MAX_AGE_MS);
    }

    public ContainerStickyEventIngress(ClusterManager clusterManager, String localNodeId,
                                       BiConsumer<String, Serializable> localExecutor, long maxAgeMs) {
        Objects.requireNonNull(clusterManager, "clusterManager");
        this.localExecutor = Objects.requireNonNull(localExecutor, "localExecutor");
        String id = localNodeId;
        if (id == null || id.isBlank()) {
            id = clusterManager.getNodeId();
        }
        this.localNodeId = (id == null || id.isBlank()) ? "local" : id;
        this.maxAgeMs = maxAgeMs;
        this.cache = clusterManager.getCache(CACHE_NAME, CacheMode.DIST_SYNC);
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        cache.addListener(this);
        started = true;
        LOG.info("Container sticky-event ingress started node={} (cache={}, maxAgeMs={})",
                localNodeId, CACHE_NAME, maxAgeMs);
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        cache.removeListener(this);
        started = false;
        LOG.info("Container sticky-event ingress stopped node={}", localNodeId);
    }

    public String localNodeId() {
        return localNodeId;
    }

    /**
     * Hand an event to the node that owns {@code activityId}.
     *
     * @param targetNodeId node holding the client connection
     * @param activityId   activity-context name (the correlation key)
     * @param originRaName which RA produced it — informational only
     * @param eventType    event class simple name
     * @param payload      portable, allow-list-clean POJO
     * @return {@code true} when an envelope was published
     */
    public boolean forward(String targetNodeId, String activityId, String originRaName,
                           String eventType, Serializable payload) {
        if (targetNodeId == null || targetNodeId.equals(localNodeId)) {
            return false;
        }
        StickyEventEnvelope env = StickyEventEnvelope.of(targetNodeId, localNodeId, activityId,
                originRaName, eventType, payload);
        // ADR 0007 K — only the target removes an envelope; if the target died the
        // entry used to stay forever. The lifespan equals the staleness cut-off.
        cache.put(env.envelopeId(), env, maxAgeMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        LOG.debug("Forwarded activity={} type={} from ra={} → node={}",
                activityId, eventType, originRaName, targetNodeId);
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
        try {
            if (env.isStale(System.currentTimeMillis(), maxAgeMs)) {
                LOG.warn("Dropping stale ingress event activity={} type={} from node={} (age={}ms)",
                        env.activityId(), env.eventType(), env.sourceNodeId(),
                        System.currentTimeMillis() - env.createdAtEpochMs());
                return;
            }
            localExecutor.accept(env.activityId(), env.payload());
        } catch (RuntimeException e) {
            LOG.error("Ingress event delivery failed activity={} type={} from ra={}",
                    env.activityId(), env.eventType(), env.raName(), e);
        } finally {
            cache.remove(event.getKey());
        }
    }

    @Override
    public String toString() {
        return "ContainerStickyEventIngress[node=" + localNodeId + ", started=" + started + ']';
    }
}
