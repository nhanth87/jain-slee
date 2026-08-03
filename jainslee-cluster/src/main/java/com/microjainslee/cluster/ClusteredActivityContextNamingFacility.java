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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityContextNamingFacility;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Production P2.2 — Infinispan-backed Activity Context Naming Facility.
 *
 * <p>Distributed replacement for the in-memory
 * {@code InMemoryActivityContextNamingFacility}. The cache is named
 * {@value #CACHE_NAME} and is opened in
 * {@link CacheMode#DIST_SYNC DIST_SYNC} mode.
 *
 * <h2>Marshalling model</h2>
 * The cluster cache stores only the <em>activity-context name</em>
 * ({@code Cache&lt;String,String&gt;}), never a live
 * {@link ActivityContextInterface}. Live ACI instances (locks, SBB graphs)
 * stay in a node-local map. Cross-node {@link #lookup(String)} returns a
 * {@link NamedActivityContext} handle that carries the name only.
 *
 * <p>Wire-in: loaded reflectively by
 * {@code MicroSleeContainer.bindActivityContextNamingFacility(Object)}.
 */
public final class ClusteredActivityContextNamingFacility implements ActivityContextNamingFacility {

    public static final String CACHE_NAME = "slee-acnf";

    private static final Logger LOG = LogManager.getLogger(ClusteredActivityContextNamingFacility.class);

    /** Distributed name → activity-context name (usually equal to the bind key). */
    private final Cache<String, String> cache;
    /** Node-local live ACI references for same-JVM lookup / attach. */
    private final ConcurrentMap<String, ActivityContextInterface> localLive = new ConcurrentHashMap<>();
    private final ClusterManager clusterManager;

    public ClusteredActivityContextNamingFacility(ClusterManager clusterMgr) {
        Objects.requireNonNull(clusterMgr, "clusterMgr");
        if (!clusterMgr.isClustered()) {
            throw new IllegalArgumentException(
                    "ClusteredActivityContextNamingFacility requires a clustered "
                            + "ClusterManager (got local mode).");
        }
        this.clusterManager = clusterMgr;
        this.cache = clusterMgr.<String, String>getCache(CACHE_NAME, CacheMode.DIST_SYNC);
        LOG.info("ClusteredActivityContextNamingFacility ready: cache={} mode={} node={}",
                CACHE_NAME, cache.getCacheConfiguration().clustering().cacheMode(),
                clusterMgr.getNodeId());
    }

    /** Package-private for tests. */
    Cache<String, String> getCache() {
        return cache;
    }

    public ClusterManager getClusterManager() {
        return clusterManager;
    }

    @Override
    public void bind(String name, ActivityContextInterface aci) {
        if (name == null || aci == null) {
            throw new IllegalArgumentException("name and aci are required");
        }
        String acName = aci.getActivityContextName();
        if (acName == null || acName.isBlank()) {
            acName = name;
        }
        localLive.put(name, aci);
        cache.put(name, acName);
    }

    @Override
    public ActivityContextInterface lookup(String name) {
        if (name == null) {
            return null;
        }
        ActivityContextInterface live = localLive.get(name);
        if (live != null) {
            return live;
        }
        String acName = cache.get(name);
        if (acName == null) {
            return null;
        }
        return new NamedActivityContext(acName);
    }

    @Override
    public void unbind(String name) {
        if (name == null) {
            return;
        }
        localLive.remove(name);
        cache.remove(name);
    }

    @Override
    public Set<String> names() {
        Set<String> snapshot = new LinkedHashSet<>();
        for (String key : cache.keySet()) {
            snapshot.add(key);
        }
        return Collections.unmodifiableSet(snapshot);
    }

    @Override
    public void clear() {
        localLive.clear();
        cache.clear();
    }

    @Override
    public java.util.Collection<ActivityContextInterface> getBoundContexts() {
        List<ActivityContextInterface> snapshot = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var e : localLive.entrySet()) {
            seen.add(e.getKey());
            snapshot.add(e.getValue());
        }
        for (String key : cache.keySet()) {
            if (seen.add(key)) {
                String acName = cache.get(key);
                if (acName != null) {
                    snapshot.add(new NamedActivityContext(acName));
                }
            }
        }
        return Collections.unmodifiableList(snapshot);
    }
}
