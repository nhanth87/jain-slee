/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.ms.api.ServiceReadinessView;
import com.microjainslee.ms.api.ServiceState;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Owns Infinispan cache lifecycle for ms queue transport.
 * Reuses {@link ClusterManager#getCacheManager()} — never creates a second manager.
 *
 * <p>In cluster mode every node must {@link #ensureServiceCaches(Collection)
 * pre-define} inbox caches for <em>all</em> known services (not only local
 * ones). Clustered cache listeners broadcast remove/add callables that call
 * {@code getCache} on every peer; missing definitions yield
 * {@code ISPN000436: Cache 'slee.queue.*' has been requested, but no matching
 * cache configuration exists} during peer shutdown.
 */
public final class IspnTransportManager implements ServiceReadinessView {

    public static final String STATE_CACHE = "slee.service.state";
    public static final String REPLY_CACHE = "slee.reply";

    private final ClusterManager clusterManager;
    private final EmbeddedCacheManager cacheManager;
    private final String nodeId;

    public IspnTransportManager(ClusterManager clusterManager) {
        this.clusterManager = Objects.requireNonNull(clusterManager, "clusterManager");
        this.cacheManager = clusterManager.getCacheManager();
        this.nodeId = clusterManager.getNodeId();
    }

    public String nodeId() {
        return nodeId;
    }

    public ClusterManager clusterManager() {
        return clusterManager;
    }

    public static String inboxCacheName(String serviceName) {
        return "slee.queue." + serviceName;
    }

    /**
     * Define + start shared reply/state caches and an inbox cache per service
     * name. Safe to call repeatedly. Call once at MS bootstrap with the full
     * descriptor set so peers survive clustered-listener teardown.
     */
    public void ensureServiceCaches(Collection<String> serviceNames) {
        Objects.requireNonNull(serviceNames, "serviceNames");
        replyCache();
        stateCache();
        for (String name : serviceNames) {
            if (name != null && !name.isBlank()) {
                inboxCache(name);
            }
        }
    }

    public Cache<String, SleeQueueEntry> inboxCache(String serviceName) {
        return getOrCreate(inboxCacheName(serviceName), inboxConfig());
    }

    public Cache<String, SleeQueueEntry> replyCache() {
        return getOrCreate(REPLY_CACHE, replyConfig());
    }

    public Cache<String, ServiceStateRecord> stateCache() {
        CacheMode mode = clusterManager.isClusterMode() ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
        return getOrCreate(STATE_CACHE, stateConfig(mode));
    }

    public void publishState(String serviceName, ServiceState state) {
        stateCache().put(
                serviceName,
                new ServiceStateRecord(serviceName, state, nodeId, System.currentTimeMillis()));
    }

    @Override
    public ServiceState stateOf(String serviceName) {
        ServiceStateRecord rec = stateCache().get(serviceName);
        if (rec == null) {
            return ServiceState.STOPPED;
        }
        // Peer crash / hard kill often leaves READY in the REPL state cache.
        // If the publishing node is gone from the cluster view, treat as STOPPED
        // so callers fail-fast instead of waiting for a queue consumer that
        // will never reply.
        if ((rec.state() == ServiceState.READY || rec.state() == ServiceState.DEGRADED)
                && !clusterManager.isNodePresent(rec.nodeId())) {
            return ServiceState.STOPPED;
        }
        return rec.state();
    }

    private <K, V> Cache<K, V> getOrCreate(String name, org.infinispan.configuration.cache.Configuration cfg) {
        if (!cacheManager.cacheExists(name)) {
            cacheManager.defineConfiguration(name, cfg);
        }
        return cacheManager.getCache(name);
    }

    private org.infinispan.configuration.cache.Configuration inboxConfig() {
        CacheMode mode = clusterManager.isClusterMode() ? CacheMode.DIST_SYNC : CacheMode.LOCAL;
        ConfigurationBuilder b = new ConfigurationBuilder();
        b.clustering().cacheMode(mode);
        b.memory().maxCount(10_000);
        if (clusterManager.isClusterMode()) {
            b.clustering().stateTransfer().awaitInitialTransfer(false);
        }
        return b.build();
    }

    private org.infinispan.configuration.cache.Configuration replyConfig() {
        CacheMode mode = clusterManager.isClusterMode() ? CacheMode.DIST_SYNC : CacheMode.LOCAL;
        ConfigurationBuilder b = new ConfigurationBuilder();
        b.clustering().cacheMode(mode);
        b.memory().maxCount(100_000);
        b.expiration().lifespan(30, TimeUnit.SECONDS);
        if (clusterManager.isClusterMode()) {
            b.clustering().stateTransfer().awaitInitialTransfer(false);
        }
        return b.build();
    }

    private org.infinispan.configuration.cache.Configuration stateConfig(CacheMode mode) {
        ConfigurationBuilder b = new ConfigurationBuilder();
        b.clustering().cacheMode(mode);
        if (clusterManager.isClusterMode()) {
            b.clustering().stateTransfer().awaitInitialTransfer(false);
        }
        return b.build();
    }
}
