/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * ISPN sticky command bus — reuses {@link Ss7DialogClusterCaches} (same
 * {@code ClusterManager}), never a second fabric.
 *
 * <p>Producer: {@link #forward(String, Ss7Command)}. Consumer: cache listener
 * delivers envelopes whose {@code targetNodeId} matches this node.
 */
@Listener(clustered = true, observation = Listener.Observation.POST)
public final class IspnStickyCommandBus {

    private static final Logger LOG = LogManager.getLogger(IspnStickyCommandBus.class);

    private final String localNodeId;
    private final Cache<String, Object> cache;
    private final Consumer<Ss7Command> localExecutor;
    private volatile boolean started;

    public IspnStickyCommandBus(
            String localNodeId,
            Ss7DialogClusterCaches caches,
            Consumer<Ss7Command> localExecutor) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        Objects.requireNonNull(caches, "caches");
        this.cache = caches.stickyCommandCache();
        this.localExecutor = Objects.requireNonNull(localExecutor, "localExecutor");
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        cache.addListener(this);
        started = true;
        LOG.info("[ra-jss7] sticky command bus started node={}", localNodeId);
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        cache.removeListener(this);
        started = false;
        LOG.info("[ra-jss7] sticky command bus stopped node={}", localNodeId);
    }

    public void forward(String targetNodeId, Ss7Command command) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(command, "command");
        Ss7StickyCommandEnvelope env = Ss7StickyCommandEnvelope.of(targetNodeId, localNodeId, command);
        cache.put(env.envelopeId(), env);
        LOG.debug("[ra-jss7] sticky forward dialog={} → node={}", command.dialogId(), targetNodeId);
    }

    @CacheEntryCreated
    public void onCreated(CacheEntryCreatedEvent<String, Object> event) {
        if (event.isPre()) {
            return;
        }
        Object value = event.getValue();
        if (!(value instanceof Ss7StickyCommandEnvelope env)) {
            return;
        }
        if (!localNodeId.equals(env.targetNodeId())) {
            return;
        }
        try {
            localExecutor.accept(env.command());
        } catch (RuntimeException e) {
            LOG.warn("[ra-jss7] sticky command execution failed dialog={}: {}",
                    env.command().dialogId(), e.toString());
        } finally {
            cache.remove(event.getKey());
        }
    }
}
