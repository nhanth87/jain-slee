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
 * Ensures SS7/TCAP dialog affinity caches on an existing {@link ClusterManager}
 * (same EmbeddedCacheManager as SBB/ACNF/MS — never a second fabric).
 *
 * <p>P1: ra-jss7 write-through + sticky command bus use these caches
 * (see {@code docs/adr/0001-ss7-ra-nn-tcap-failover.md}).
 */
public final class Ss7DialogClusterCaches {

    private final ClusterManager clusterManager;
    private final Cache<String, TcapDialogMeta> metaCache;
    private final Cache<String, String> byRemoteCache;
    private final Cache<String, RaDialogOwner> ownerCache;
    private final Cache<String, TcapDialogSnapshotPayload> snapshotCache;
    private final Cache<String, Object> stickyCommandCache;
    private final Cache<String, SctpEndpointLease> endpointLeaseCache;

    private Ss7DialogClusterCaches(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
        // Cluster: REPL_SYNC so ownership/meta fences are visible before outbound
        // CONTINUE. Local/R&D: LOCAL — Infinispan rejects clustered modes
        // without a JGroups transport (same pattern as IspnTransportManager).
        CacheMode mode = clusterManager.isClusterMode() ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
        // Sticky commands: DIST_SYNC in cluster so only needed copies move; LOCAL otherwise.
        CacheMode stickyMode = clusterManager.isClusterMode() ? CacheMode.DIST_SYNC : CacheMode.LOCAL;
        this.metaCache = clusterManager.getCache(Ss7DialogCacheNames.TCAP_DIALOG_META, mode);
        this.byRemoteCache = clusterManager.getCache(Ss7DialogCacheNames.TCAP_DIALOG_BY_REMOTE, mode);
        this.ownerCache = clusterManager.getCache(Ss7DialogCacheNames.RA_DIALOG_OWNER, mode);
        this.snapshotCache = clusterManager.getCache(Ss7DialogCacheNames.TCAP_DIALOG_SNAPSHOT, mode);
        this.stickyCommandCache = clusterManager.getCache(Ss7DialogCacheNames.RA_STICKY_COMMANDS, stickyMode);
        this.endpointLeaseCache = clusterManager.getCache(Ss7DialogCacheNames.SCTP_ENDPOINT_LEASE, mode);
    }

    /**
     * Idempotent: define (if needed) and return the three SS7 dialog caches.
     */
    public static Ss7DialogClusterCaches ensureCaches(ClusterManager clusterManager) {
        Objects.requireNonNull(clusterManager, "clusterManager");
        return new Ss7DialogClusterCaches(clusterManager);
    }

    public ClusterManager clusterManager() {
        return clusterManager;
    }

    public Cache<String, TcapDialogMeta> metaCache() {
        return metaCache;
    }

    public Cache<String, String> byRemoteCache() {
        return byRemoteCache;
    }

    public Cache<String, RaDialogOwner> ownerCache() {
        return ownerCache;
    }

    /** P2 portable snapshots for CONTINUE takeover (never live DialogImpl). */
    public Cache<String, TcapDialogSnapshotPayload> snapshotCache() {
        return snapshotCache;
    }

    /**
     * Sticky outbound command envelopes ({@code envelopeId → Object}).
     * Values are typed by ra-jss7 ({@code Ss7StickyCommandEnvelope}); stored as
     * {@link Object} so this module stays free of RA imports.
     */
    public Cache<String, Object> stickyCommandCache() {
        return stickyCommandCache;
    }

    /** SCTP {@code ip:port} → lease (n-n endpoint fence). */
    public Cache<String, SctpEndpointLease> endpointLeaseCache() {
        return endpointLeaseCache;
    }

    public void putSnapshot(TcapDialogSnapshotPayload snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        snapshotCache.put(snapshot.dialogKey(), snapshot);
    }

    public TcapDialogSnapshotPayload getSnapshot(String dialogKey) {
        return dialogKey == null ? null : snapshotCache.get(dialogKey);
    }

    public void removeSnapshot(String dialogKey) {
        if (dialogKey != null) {
            snapshotCache.remove(dialogKey);
        }
    }

    /**
     * Put meta and optional remote index. Does not touch ownership.
     */
    public void putMeta(TcapDialogMeta meta) {
        Objects.requireNonNull(meta, "meta");
        metaCache.put(meta.dialogKey(), meta);
        if (meta.remoteOtid() != null) {
            byRemoteCache.put(meta.remoteIndexKey(), meta.dialogKey());
        }
    }

    /** Remove meta and best-effort remote index entry. */
    public void removeMeta(String dialogKey) {
        if (dialogKey == null) {
            return;
        }
        TcapDialogMeta previous = metaCache.remove(dialogKey);
        if (previous != null && previous.remoteOtid() != null) {
            byRemoteCache.remove(previous.remoteIndexKey());
        }
        snapshotCache.remove(dialogKey);
    }

    /**
     * Create ownership if absent ({@code generation == 0} expected for first write).
     *
     * @return {@code true} when this caller installed the first owner
     */
    public boolean tryPutOwnerIfAbsent(RaDialogOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return ownerCache.putIfAbsent(owner.dialogId(), owner) == null;
    }

    /** Upsert ownership (refresh timestamp / same owner). */
    public void putOwner(RaDialogOwner owner) {
        Objects.requireNonNull(owner, "owner");
        ownerCache.put(owner.dialogId(), owner);
    }

    public RaDialogOwner getOwner(String dialogId) {
        return dialogId == null ? null : ownerCache.get(dialogId);
    }

    public void removeOwner(String dialogId) {
        if (dialogId != null) {
            ownerCache.remove(dialogId);
        }
    }

    /**
     * CAS-style ownership transfer: replace {@code expected} with a bumped generation.
     *
     * @return {@code true} when the replace succeeded
     */
    public boolean tryClaimOwnership(RaDialogOwner expected, String newOwnerNodeId, String newRaName,
                                     long updatedAtEpochMs) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(newOwnerNodeId, "newOwnerNodeId");
        RaDialogOwner next = expected.withOwner(
                newOwnerNodeId, newRaName, expected.generation() + 1, updatedAtEpochMs);
        return ownerCache.replace(expected.dialogId(), expected, next);
    }

    /**
     * Claim an SCTP endpoint if absent ({@code generation == 0} first write).
     *
     * @return {@code true} when this caller installed the first lease
     */
    public boolean tryPutEndpointLeaseIfAbsent(SctpEndpointLease lease) {
        Objects.requireNonNull(lease, "lease");
        return endpointLeaseCache.putIfAbsent(lease.endpointKey(), lease) == null;
    }

    public SctpEndpointLease getEndpointLease(String endpointKey) {
        return endpointKey == null ? null : endpointLeaseCache.get(endpointKey);
    }

    /**
     * CAS transfer of an SCTP endpoint lease (VIP takeover fence).
     *
     * @return {@code true} when replace succeeded
     */
    public boolean tryClaimEndpointLease(SctpEndpointLease expected, String newOwnerNodeId, long updatedAtEpochMs) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(newOwnerNodeId, "newOwnerNodeId");
        SctpEndpointLease next = expected.withOwner(
                newOwnerNodeId, expected.generation() + 1, updatedAtEpochMs);
        return endpointLeaseCache.replace(expected.endpointKey(), expected, next);
    }

    public void putEndpointLease(SctpEndpointLease lease) {
        Objects.requireNonNull(lease, "lease");
        endpointLeaseCache.put(lease.endpointKey(), lease);
    }

    public void removeEndpointLease(String endpointKey) {
        if (endpointKey != null) {
            endpointLeaseCache.remove(endpointKey);
        }
    }
}
