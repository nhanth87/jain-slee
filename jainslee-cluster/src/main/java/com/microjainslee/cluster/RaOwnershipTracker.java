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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Generic sticky ownership + portable session meta (ADR 0002 / Gate A).
 *
 * <p>RAs own HA; SBBs never call this. Null {@link RaActivityOwnerCaches} =
 * local-only single-JVM mode.
 */
public final class RaOwnershipTracker {

    private static final Logger LOG = LogManager.getLogger(RaOwnershipTracker.class);

    private final String localNodeId;
    private final String raName;
    private final RaActivityOwnerCaches caches; // nullable
    private final RaHaMetrics metrics;
    private final ConcurrentMap<String, RaDialogOwner> localOwners = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RaSessionMeta> localMeta = new ConcurrentHashMap<>();

    public RaOwnershipTracker(
            String localNodeId,
            String raName,
            RaActivityOwnerCaches caches,
            RaHaMetrics metrics) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.raName = Objects.requireNonNull(raName, "raName");
        this.caches = caches;
        this.metrics = metrics == null ? new RaHaMetrics() : metrics;
    }

    public static RaOwnershipTracker localOnly(String localNodeId, String raName) {
        return new RaOwnershipTracker(localNodeId, raName, null, new RaHaMetrics());
    }

    public String localNodeId() {
        return localNodeId;
    }

    public String raName() {
        return raName;
    }

    public RaHaMetrics metrics() {
        return metrics;
    }

    public boolean isClustered() {
        return caches != null;
    }

    public void onOpened(String activityId, String status, Map<String, String> attrs) {
        if (activityId == null || activityId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        RaDialogOwner owner = new RaDialogOwner(activityId, localNodeId, raName, 0L, now);
        localOwners.put(activityId, owner);
        if (caches != null) {
            if (!caches.tryPutOwnerIfAbsent(owner)) {
                RaDialogOwner existing = caches.getOwner(activityId);
                if (existing != null) {
                    if (!localNodeId.equals(existing.ownerNodeId())) {
                        LOG.warn("[{}] activity {} already owned by node={} — local claim lost",
                                raName, activityId, existing.ownerNodeId());
                    }
                    localOwners.put(activityId, existing);
                    owner = existing;
                }
            } else {
                metrics.ownerClaim();
            }
        } else {
            metrics.ownerClaim();
        }
        RaSessionMeta meta = new RaSessionMeta(activityId, raName, status, attrs, now);
        localMeta.put(activityId, meta);
        if (caches != null) {
            caches.putSessionMeta(meta);
        }
        metrics.metaPut();
    }

    public void onTouched(String activityId, String status, Map<String, String> attrs) {
        if (activityId == null || activityId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        RaDialogOwner owner = localOwners.get(activityId);
        if (owner == null && caches != null) {
            owner = caches.getOwner(activityId);
        }
        if (owner == null) {
            onOpened(activityId, status, attrs);
            return;
        }
        if (localNodeId.equals(owner.ownerNodeId())) {
            RaDialogOwner refreshed = new RaDialogOwner(
                    activityId, owner.ownerNodeId(), owner.raName(), owner.generation(), now);
            localOwners.put(activityId, refreshed);
            if (caches != null) {
                caches.putOwner(refreshed);
            }
        }
        RaSessionMeta previous = localMeta.get(activityId);
        Map<String, String> merged = attrs != null ? attrs
                : (previous != null ? previous.attrs() : Map.of());
        String st = status != null ? status : (previous != null ? previous.status() : "Active");
        RaSessionMeta meta = new RaSessionMeta(activityId, raName, st, merged, now);
        localMeta.put(activityId, meta);
        if (caches != null) {
            caches.putSessionMeta(meta);
        }
        metrics.metaPut();
    }

    public void onClosed(String activityId) {
        if (activityId == null || activityId.isBlank()) {
            return;
        }
        localOwners.remove(activityId);
        localMeta.remove(activityId);
        if (caches != null) {
            caches.removeOwner(activityId);
            caches.removeSessionMeta(activityId);
        }
    }

    public Optional<RaDialogOwner> lookupOwner(String activityId) {
        if (activityId == null || activityId.isBlank()) {
            return Optional.empty();
        }
        RaDialogOwner local = localOwners.get(activityId);
        if (local != null) {
            return Optional.of(local);
        }
        if (caches != null) {
            RaDialogOwner remote = caches.getOwner(activityId);
            if (remote != null) {
                localOwners.put(activityId, remote);
                return Optional.of(remote);
            }
        }
        return Optional.empty();
    }

    public Optional<RaSessionMeta> lookupMeta(String activityId) {
        if (activityId == null) {
            return Optional.empty();
        }
        RaSessionMeta local = localMeta.get(activityId);
        if (local != null) {
            return Optional.of(local);
        }
        if (caches != null) {
            return Optional.ofNullable(caches.getSessionMeta(activityId));
        }
        return Optional.empty();
    }

    public void clearAll() {
        localOwners.clear();
        localMeta.clear();
    }
}
