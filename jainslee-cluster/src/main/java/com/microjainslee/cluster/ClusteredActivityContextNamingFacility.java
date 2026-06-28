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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityContextNamingFacility;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Production P2.2 — Infinispan-backed Activity Context Naming Facility.
 *
 * <p>Distributed replacement for the in-memory
 * {@code InMemoryActivityContextNamingFacility}. The cache is named
 * {@value #CACHE_NAME} and is opened in
 * {@link CacheMode#DIST_SYNC DIST_SYNC} mode: each key is owned by a
 * single node (consistent hashing) and writes are replicated
 * synchronously to the backup owners. This is the right trade-off
 * for an ACNF: a name is small, lookups are hot, and we cannot serve
 * a stale value to a peer that just bound it.
 *
 * <p><b>Wire-in.</b> The class is loaded reflectively by
 * {@code MicroSleeContainer.bindActivityContextNamingFacility(Object)}
 * — jainslee-core does not depend on jainslee-cluster at compile time.
 * The reflective contract requires a single-arg constructor accepting
 * a {@link ClusterManager}.
 *
 * <h2>Marshalling caveat (P5 follow-up)</h2>
 * The default Infinispan marshaller is Java serialization. Both
 * {@link com.microjainslee.api.ActivityContextInterface} and the
 * concrete {@code InMemoryActivityContext} currently in jainslee-core
 * do <b>not</b> implement {@link java.io.Serializable}, so a live
 * cross-node call will throw
 * {@link org.infinispan.commons.marshall.NotSerializableException} at
 * runtime. This is acceptable for P2.2 (R&amp;D validation of the
 * wire path) and is explicitly tracked as a P5 task: the production
 * fix is to switch to an Infinispan Protostream schema
 * ({@code slee.proto}) so {@code ActivityContextInterface} can be
 * marshalled across versions and JVMs without {@code Serializable}.
 *
 * <p><b>R&amp;D only — never for production.</b>
 */
public final class ClusteredActivityContextNamingFacility implements ActivityContextNamingFacility {

    /**
     * Name of the Infinispan cache that backs the facility. All
     * instances of {@code ClusteredActivityContextNamingFacility}
     * that share a {@link ClusterManager} see the same cache.
     */
    public static final String CACHE_NAME = "slee-acnf";

    private static final Logger LOG = LogManager.getLogger(ClusteredActivityContextNamingFacility.class);

    private final Cache<String, ActivityContextInterface> cache;
    private final ClusterManager clusterManager;

    /**
     * Build a clustered ACNF backed by the cache
     * {@value #CACHE_NAME} in {@link CacheMode#DIST_SYNC} mode.
     *
     * @param clusterMgr the cluster manager that owns the embedded
     *                   Infinispan cache. Must be clustered
     *                   ({@link ClusterManager#isClustered()} returns
     *                   {@code true}) so that JGroups can replicate
     *                   the cache entries.
     */
    public ClusteredActivityContextNamingFacility(ClusterManager clusterMgr) {
        Objects.requireNonNull(clusterMgr, "clusterMgr");
        if (!clusterMgr.isClustered()) {
            throw new IllegalArgumentException(
                    "ClusteredActivityContextNamingFacility requires a clustered "
                            + "ClusterManager (got local mode). Use "
                            + "ClusterManager.clustered(\"name\") to enable JGroups.");
        }
        this.clusterManager = clusterMgr;
        this.cache = clusterMgr.<String, ActivityContextInterface>getCache(
                CACHE_NAME, CacheMode.DIST_SYNC);
        LOG.info("ClusteredActivityContextNamingFacility ready: cache={} mode={} node={}",
                CACHE_NAME, cache.getCacheConfiguration().clustering().cacheMode(),
                clusterMgr.getNodeId());
    }

    /**
     * @return the underlying Infinispan cache. Package-private so
     *         tests can assert on cache size and configuration; not
     *         part of the public API.
     */
    Cache<String, ActivityContextInterface> getCache() {
        return cache;
    }

    /**
     * @return the cluster manager that owns the cache.
     */
    public ClusterManager getClusterManager() {
        return clusterManager;
    }

    // -----------------------------------------------------------------
    // ActivityContextNamingFacility
    // -----------------------------------------------------------------

    /**
     * Associate {@code name} with {@code aci} in the cluster cache.
     * The write is replicated synchronously to the backup owners of
     * the key (DIST_SYNC semantics).
     *
     * @throws IllegalArgumentException if either argument is null
     */
    @Override
    public void bind(String name, ActivityContextInterface aci) {
        if (name == null || aci == null) {
            throw new IllegalArgumentException("name and aci are required");
        }
        cache.put(name, aci);
    }

    /**
     * Look up the activity context bound to {@code name}. Returns
     * {@code null} when no binding exists.
     *
     * <p>The lookup is fetched from the owner node (and synchronously
     * from the local cache when this node is the owner) — DIST_SYNC
     * guarantees the read is consistent with the latest write.
     */
    public ActivityContextInterface lookup(String name) {
        if (name == null) {
            return null;
        }
        return cache.get(name);
    }

    /**
     * Remove the binding for {@code name}. Silent no-op when the
     * name is not bound.
     */
    public void unbind(String name) {
        if (name == null) {
            return;
        }
        cache.remove(name);
    }

    /**
     * @return the set of all names currently bound in the cluster.
     *         Returns a snapshot — the underlying set is not
     *         thread-safe to iterate concurrently with writes.
     */
    public Set<String> names() {
        // Infinispan's Cache.keySet() returns a view backed by the
        // local persistent layer. Copy it into a HashSet to give
        // callers a stable snapshot.
        Set<String> snapshot = new LinkedHashSet<String>();
        for (String key : cache.keySet()) {
            snapshot.add(key);
        }
        return Collections.unmodifiableSet(snapshot);
    }

    /**
     * Remove every binding from the cluster cache. Best-effort: with
     * {@code DIST_SYNC} the operation is synchronous on the local
     * owner but the remote nodes observe the deletions through the
     * standard invalidation path (eventual consistency).
     */
    public void clear() {
        cache.clear();
    }

    /**
     * @return the set of bound activity contexts as a snapshot.
     *         Reflects the local cache view; remote-only entries may
     *         not appear until the local cache receives an
     *         invalidation event.
     */
    public java.util.Collection<ActivityContextInterface> getBoundContexts() {
        java.util.List<ActivityContextInterface> snapshot =
                new java.util.ArrayList<ActivityContextInterface>();
        for (ActivityContextInterface value : cache.values()) {
            if (value != null) {
                snapshot.add(value);
            }
        }
        return java.util.Collections.unmodifiableList(snapshot);
    }
}
