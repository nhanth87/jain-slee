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

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;

import java.util.Objects;

/**
 * Per-RA owner / sticky / session-meta caches on one {@link ClusterManager}.
 */
public final class RaActivityOwnerCaches {

    private final ClusterManager clusterManager;
    private final String raName;
    private final Cache<String, RaDialogOwner> ownerCache;
    private final Cache<String, Object> stickyCommandCache;
    private final Cache<String, RaSessionMeta> sessionMetaCache;

    private RaActivityOwnerCaches(ClusterManager clusterManager, String raName) {
        this.clusterManager = clusterManager;
        this.raName = raName;
        CacheMode mode = clusterManager.isClusterMode() ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
        CacheMode stickyMode = clusterManager.isClusterMode() ? CacheMode.DIST_SYNC : CacheMode.LOCAL;
        this.ownerCache = clusterManager.getCache(RaActivityCacheNames.owner(raName), mode);
        this.stickyCommandCache = clusterManager.getCache(
                RaActivityCacheNames.stickyCommands(raName), stickyMode);
        this.sessionMetaCache = clusterManager.getCache(
                RaActivityCacheNames.sessionMeta(raName), mode);
    }

    public static RaActivityOwnerCaches ensureCaches(ClusterManager clusterManager, String raName) {
        Objects.requireNonNull(clusterManager, "clusterManager");
        Objects.requireNonNull(raName, "raName");
        return new RaActivityOwnerCaches(clusterManager, raName);
    }

    public ClusterManager clusterManager() {
        return clusterManager;
    }

    public String raName() {
        return raName;
    }

    public Cache<String, RaDialogOwner> ownerCache() {
        return ownerCache;
    }

    public Cache<String, Object> stickyCommandCache() {
        return stickyCommandCache;
    }

    public Cache<String, RaSessionMeta> sessionMetaCache() {
        return sessionMetaCache;
    }

    public boolean tryPutOwnerIfAbsent(RaDialogOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return ownerCache.putIfAbsent(owner.dialogId(), owner) == null;
    }

    public void putOwner(RaDialogOwner owner) {
        Objects.requireNonNull(owner, "owner");
        ownerCache.put(owner.dialogId(), owner);
    }

    public RaDialogOwner getOwner(String activityId) {
        return activityId == null ? null : ownerCache.get(activityId);
    }

    public void removeOwner(String activityId) {
        if (activityId != null) {
            ownerCache.remove(activityId);
        }
    }

    public boolean tryClaimOwnership(
            RaDialogOwner expected, String newOwnerNodeId, String newRaName, long updatedAtEpochMs) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(newOwnerNodeId, "newOwnerNodeId");
        RaDialogOwner next = expected.withOwner(
                newOwnerNodeId, newRaName, expected.generation() + 1, updatedAtEpochMs);
        return ownerCache.replace(expected.dialogId(), expected, next);
    }

    public void putSessionMeta(RaSessionMeta meta) {
        Objects.requireNonNull(meta, "meta");
        MarshallingAllowList.assertMarshallable("session-meta", meta);
        sessionMetaCache.put(meta.activityId(), meta);
    }

    public RaSessionMeta getSessionMeta(String activityId) {
        return activityId == null ? null : sessionMetaCache.get(activityId);
    }

    public void removeSessionMeta(String activityId) {
        if (activityId != null) {
            sessionMetaCache.remove(activityId);
        }
    }
}
