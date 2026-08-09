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
import org.infinispan.Cache;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;

import java.io.Serializable;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Generic ISPN sticky command bus (ADR 0002) — same {@link ClusterManager} fabric.
 */
@Listener(clustered = true, observation = Listener.Observation.POST)
public final class RaStickyCommandBus {

    private static final Logger LOG = LogManager.getLogger(RaStickyCommandBus.class);

    private final String localNodeId;
    private final String raName;
    private final Cache<String, Object> cache;
    private final Consumer<RaStickyCommandEnvelope> localExecutor;
    private final RaHaMetrics metrics;
    private volatile boolean started;

    public RaStickyCommandBus(
            String localNodeId,
            RaActivityOwnerCaches caches,
            Consumer<RaStickyCommandEnvelope> localExecutor,
            RaHaMetrics metrics) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        Objects.requireNonNull(caches, "caches");
        this.raName = caches.raName();
        this.cache = caches.stickyCommandCache();
        this.localExecutor = Objects.requireNonNull(localExecutor, "localExecutor");
        this.metrics = metrics == null ? new RaHaMetrics() : metrics;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        cache.addListener(this);
        started = true;
        LOG.info("[{}] sticky command bus started node={}", raName, localNodeId);
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        cache.removeListener(this);
        started = false;
        LOG.info("[{}] sticky command bus stopped node={}", raName, localNodeId);
    }

    public void forward(String targetNodeId, String activityId, Serializable payload) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        RaStickyCommandEnvelope env = RaStickyCommandEnvelope.of(
                targetNodeId, localNodeId, activityId, payload);
        cache.put(env.envelopeId(), env);
        metrics.stickyForward();
        LOG.debug("[{}] sticky forward activity={} → node={}", raName, activityId, targetNodeId);
    }

    @CacheEntryCreated
    public void onCreated(CacheEntryCreatedEvent<String, Object> event) {
        if (event.isPre()) {
            return;
        }
        Object value = event.getValue();
        if (!(value instanceof RaStickyCommandEnvelope env)) {
            return;
        }
        if (!localNodeId.equals(env.targetNodeId())) {
            return;
        }
        try {
            localExecutor.accept(env);
        } catch (RuntimeException e) {
            LOG.warn("[{}] sticky command execution failed activity={}: {}",
                    raName, env.activityId(), e.toString());
        } finally {
            cache.remove(event.getKey());
        }
    }
}
