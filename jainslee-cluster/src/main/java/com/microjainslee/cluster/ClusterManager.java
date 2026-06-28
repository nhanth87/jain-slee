/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import com.microjainslee.core.MicroSleeConfiguration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Production P2.1 — manages an Infinispan {@link EmbeddedCacheManager}
 * optionally wired to a JGroups transport.
 *
 * <p>The class encapsulates two deployment shapes:
 * <ul>
 *   <li><b>Local mode</b> (default, R&amp;D):
 *       {@link MicroSleeConfiguration#isClusterEnabled()} returns {@code false}.
 *       The Infinispan global config is built with {@code nonClusteredDefault()}
 *       so no JGroups transport is started and the cache manager behaves as a
 *       pure in-process {@link DefaultCacheManager}. This is the path used by
 *       all existing micro-jainslee unit tests, the embedded j25 example, and
 *       the Quarkus adapter when no cluster profile is active.</li>
 *   <li><b>Cluster mode</b>:
 *       {@code clusterEnabled = true}. A JGroups transport is configured with
 *       the {@code configurationFile} property pointing at
 *       {@code jgroups-tcp.xml} (or {@code jgroups-udp.xml}) and the
 *       {@code initial_hosts} property. The default transport is
 *       {@code defaultTransport()} so the embedder does not have to wire one
 *       in by hand. A node name is taken from
 *       {@link MicroSleeConfiguration#getNodeId()} or a fresh short UUID.</li>
 * </ul>
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li>The class lives in a dedicated module so {@code jainslee-core} keeps a
 *       <b>clean compile-time boundary</b> with the cluster layer. The kernel
 *       never imports this class directly &mdash; integration happens
 *       reflectively (see
 *       {@code com.microjainslee.core.MicroSleeContainer#bindCluster(Object)}).</li>
 *   <li>Both modes share the same {@link #getCache(String, CacheMode)} entry
 *       point, so embedders (P2.2 ClusteredActivityContextNamingFacility,
 *       P2.3 DistributedSbbEntityPool, P2.4 ClusterEventRouter) can write
 *       cluster-agnostic code.</li>
 *   <li>No JGroups channel is started until {@link #start()} is called. That
 *       lets callers construct + register caches eagerly and defer the
 *       network join to a deterministic point in the lifecycle.</li>
 * </ul>
 */
public class ClusterManager {

    private static final Logger LOG = LogManager.getLogger(ClusterManager.class);

    /**
     * JGroups stack files. Infinispan ships its own pre-tuned stacks at
     * {@code default-configs/default-jgroups-tcp.xml} (TCP) and
     * {@code default-configs/default-jgroups-udp.xml} (UDP). Embedders
     * may override these by setting the {@code configurationFile}
     * JGroups property explicitly through the {@code properties} map
     * of a future advanced configuration; for the default
     * {@link MicroSleeConfiguration#getClusterStack()} values we use
     * the Infinispan-shipped files because they are tuned for
     * Infinispan's marshalling layer.
     */
    private static final String DEFAULT_TCP_STACK = "default-configs/default-jgroups-tcp.xml";
    private static final String DEFAULT_UDP_STACK = "default-configs/default-jgroups-udp.xml";

    private final MicroSleeConfiguration configuration;
    private final String nodeId;
    private final boolean clusterMode;
    private final EmbeddedCacheManager cacheManager;
    private final ConcurrentMap<String, Cache<?, ?>> declaredCaches = new ConcurrentHashMap<>();

    /**
     * Build a {@code ClusterManager} from a {@link MicroSleeConfiguration}.
     * The {@code nodeIdOverride} argument wins over the configuration value
     * when non-null; this mirrors the JTA reflection pattern where the
     * container may pass a derived id (e.g. a Kubernetes pod name).
     *
     * <p>The constructor is idempotent on cache manager creation &mdash; it
     * <em>does not</em> start the JGroups transport (in cluster mode). Call
     * {@link #start()} to bring the transport up.
     *
     * @param configuration  the kernel configuration (must be non-null)
     * @param nodeIdOverride explicit node id override, or {@code null} to
     *                       fall back to {@code configuration.getNodeId()}
     *                       and finally to a fresh short UUID
     */
    public ClusterManager(MicroSleeConfiguration configuration, String nodeIdOverride) {
        this.configuration = Objects.requireNonNull(configuration,
                "MicroSleeConfiguration is required");
        this.clusterMode = configuration.isClusterEnabled();
        this.nodeId = resolveNodeId(configuration, nodeIdOverride);
        this.cacheManager = new DefaultCacheManager(buildGlobalConfiguration());
        LOG.info("ClusterManager created: nodeId={} clusterMode={} stack={} initialHosts={}",
                nodeId, clusterMode, configuration.getClusterStack(),
                configuration.getClusterInitialHosts());
    }

    private static String resolveNodeId(MicroSleeConfiguration cfg, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        String cfgNode = cfg.getNodeId();
        if (cfgNode != null && !cfgNode.isBlank()) {
            return cfgNode;
        }
        // Short UUID keeps JGroups log lines readable; full 36-char UUIDs are
        // overwhelming when there are dozens of nodes in the view.
        return "node-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private GlobalConfiguration buildGlobalConfiguration() {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        // Always set a node name &mdash; Infinispan requires it even in local
        // mode (it becomes the cache manager JMX name).
        global.transport().nodeName(nodeId);
        global.cacheManagerName("micro-jainslee-" + nodeId);
        if (clusterMode) {
            String stack = configuration.getClusterStack();
            String configFile = "tcp".equalsIgnoreCase(stack)
                    ? DEFAULT_TCP_STACK
                    : DEFAULT_UDP_STACK;
            // JGroups properties (configurationFile, initial_hosts) are
            // forwarded by the defaultTransport() builder. These are
            // exactly the properties the JGroups XML files expect.
            global.transport()
                    .defaultTransport()
                    .addProperty("configurationFile", configFile)
                    .addProperty("initial_hosts", configuration.getClusterInitialHosts())
                    .addProperty("jgroups.stack.config", configFile);
            // The JGroups thread pool is shared with the Infinispan
            // remote-command executor; named so it shows up clearly in
            // thread dumps.
            global.transport().transportThreadPool().name("jgroups-tp");
        } else {
            // No transport &mdash; explicit nonClusteredDefault so the global
            // config does not even allocate a JGroups executor.
            global.nonClusteredDefault();
        }
        return global.build();
    }

    /**
     * Start the JGroups transport if cluster mode is enabled. In local mode
     * this is a no-op (the Infinispan cache manager is already usable).
     *
     * <p>The method is idempotent; calling it twice on the same instance has
     * no effect.
     */
    public void start() {
        if (!clusterMode) {
            LOG.debug("ClusterManager.start(): local mode, no transport to start");
            return;
        }
        if (cacheManager.getStatus().isTerminated()) {
            throw new IllegalStateException("Cache manager already stopped; cannot start");
        }
        if (cacheManager.getStatus() != ComponentStatus.RUNNING) {
            cacheManager.start();
        }
        // In cluster mode, the JGroups channel is started lazily by
        // EmbeddedCacheManager.start() above. Surface the transport so the
        // embedder can see the local member id in the logs.
        LOG.info("ClusterManager started in cluster mode: nodeId={}", nodeId);
    }

    /**
     * Define and obtain a cache with the requested {@link CacheMode}. Calling
     * the method twice with the same {@code name} returns the <em>same</em>
     * cache instance (idempotent &mdash; matches Infinispan semantics).
     *
     * @param name non-null cache name
     * @param mode desired clustering mode; pass {@link CacheMode#LOCAL} to
     *             keep the cache node-local even in cluster mode (useful for
     *             transient state)
     * @param <K>  key type
     * @param <V>  value type
     * @return a started, ready-to-use Infinispan cache
     */
    public <K, V> Cache<K, V> getCache(String name, CacheMode mode) {
        Objects.requireNonNull(name, "cache name is required");
        Objects.requireNonNull(mode, "cache mode is required");
        @SuppressWarnings("unchecked")
        Cache<K, V> cached = (Cache<K, V>) declaredCaches.get(name);
        if (cached != null) {
            return cached;
        }
        ConfigurationBuilder builder = new ConfigurationBuilder();
        builder.clustering().cacheMode(mode);
        // Avoid expensive JGroups marshal validation in tests; production
        // embedders can override this by passing a custom configuration.
        if (clusterMode && mode.isClustered()) {
            builder.clustering().stateTransfer().awaitInitialTransfer(false);
        }
        Configuration configuration = builder.build();
        cacheManager.defineConfiguration(name, configuration);
        Cache<K, V> cache = cacheManager.getCache(name);
        declaredCaches.put(name, cache);
        return cache;
    }

    /**
     * Convenience overload for cluster-agnostic code: defines a cache with
     * {@link CacheMode#LOCAL}. Returns a {@code <String,String>} cache, which
     * is enough for P2.2 ClusteredActivityContextNamingFacility when
     * {@code clusterEnabled = false} (R&amp;D mode).
     */
    public Cache<String, String> getLocalStringCache(String name) {
        return getCache(name, CacheMode.LOCAL);
    }

    /** @return the local node id (effective value, including auto-generated). */
    public String getNodeId() {
        return nodeId;
    }

    /** @return {@code true} when a JGroups transport will be started. */
    public boolean isClusterMode() {
        return clusterMode;
    }

    /**
     * Alias for {@link #isClusterMode()} kept for callers that prefer the
     * Infinispan convention {@code isClustered()}. Both predicates are
     * equivalent; the cluster module always reports the same value through
     * either name.
     */
    public boolean isClustered() {
        return clusterMode;
    }

    /**
     * @return the underlying Infinispan {@link EmbeddedCacheManager}. Use
     *         sparingly &mdash; prefer {@link #getCache(String, CacheMode)}.
     *         Exposed for advanced integration tests.
     */
    public EmbeddedCacheManager getCacheManager() {
        return cacheManager;
    }

    /**
     * Stop the JGroups transport and release all cache resources. Safe to
     * call from {@code finally} blocks; idempotent.
     */
    public void stop() {
        try {
            if (cacheManager.getStatus() == ComponentStatus.RUNNING) {
                cacheManager.stop();
            }
        } catch (RuntimeException re) {
            // Best-effort shutdown. Embedders usually call this from a
            // shutdown hook; surfacing an exception would mask whatever
            // actually caused the JVM to terminate. Log and move on.
            LOG.warn("Cache manager stop failed: {}", re.getMessage());
        } finally {
            declaredCaches.clear();
        }
        LOG.info("ClusterManager stopped: nodeId={}", nodeId);
    }
}
