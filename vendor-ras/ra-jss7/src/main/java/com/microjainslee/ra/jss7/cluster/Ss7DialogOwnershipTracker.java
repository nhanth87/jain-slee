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

import com.microjainslee.cluster.RaDialogOwner;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.cluster.TcapDialogMeta;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Write-through tracker for SS7 dialog ownership / meta (ADR 0001 P1).
 *
 * <p>Always keeps a node-local map. When {@link Ss7DialogClusterCaches} is
 * present, also mirrors into Infinispan. Null-safe when no cluster is bound
 * (single-JVM / R&amp;D).
 *
 * <p>Does <strong>not</strong> store jSS7 {@code DialogImpl} — only POJO meta.
 */
public final class Ss7DialogOwnershipTracker {

    private static final Logger LOG = LogManager.getLogger(Ss7DialogOwnershipTracker.class);

    private final String localNodeId;
    private final String raName;
    private final int localPc;
    private final int localSsn;
    private final Ss7DialogClusterCaches clusterCaches; // nullable
    private final ConcurrentMap<String, RaDialogOwner> localOwners = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TcapDialogMeta> localMeta = new ConcurrentHashMap<>();

    public Ss7DialogOwnershipTracker(
            String localNodeId,
            String raName,
            int localPc,
            int localSsn,
            Ss7DialogClusterCaches clusterCaches) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.raName = Objects.requireNonNull(raName, "raName");
        this.localPc = localPc;
        this.localSsn = localSsn;
        this.clusterCaches = clusterCaches;
    }

    /** Local-only tracker (no Infinispan). */
    public static Ss7DialogOwnershipTracker localOnly(
            String localNodeId, String raName, int localPc, int localSsn) {
        return new Ss7DialogOwnershipTracker(localNodeId, raName, localPc, localSsn, null);
    }

    public String localNodeId() {
        return localNodeId;
    }

    public String raName() {
        return raName;
    }

    public boolean isClustered() {
        return clusterCaches != null;
    }

    /**
     * Claim ownership on first sight of a dialog (inbound Begin or outbound create).
     */
    public void onDialogOpened(
            String dialogId,
            long localOtid,
            byte[] remoteOtid,
            int remotePc,
            int remoteSsn,
            String trState,
            String correlationId) {
        if (dialogId == null || dialogId.isBlank() || "?".equals(dialogId)) {
            return;
        }
        long now = System.currentTimeMillis();
        RaDialogOwner owner = new RaDialogOwner(dialogId, localNodeId, raName, 0L, now);
        localOwners.put(dialogId, owner);
        if (clusterCaches != null) {
            if (!clusterCaches.tryPutOwnerIfAbsent(owner)) {
                RaDialogOwner existing = clusterCaches.getOwner(dialogId);
                if (existing != null) {
                    if (!localNodeId.equals(existing.ownerNodeId())) {
                        LOG.warn("[ra-jss7] dialog {} already owned by node={} — local claim lost",
                                dialogId, existing.ownerNodeId());
                    }
                    localOwners.put(dialogId, existing);
                    owner = existing;
                }
            }
        }
        TcapDialogMeta meta = new TcapDialogMeta(
                dialogId, localOtid, remoteOtid, localPc, localSsn, remotePc, remoteSsn,
                trState == null ? "Active" : trState, owner.ownerNodeId(), raName,
                owner.generation(), dialogId, correlationId, now);
        localMeta.put(dialogId, meta);
        if (clusterCaches != null) {
            clusterCaches.putMeta(meta);
        }
        LOG.debug("[ra-jss7] dialog opened id={} otid={} owner={}", dialogId, localOtid, localNodeId);
    }

    /** Refresh meta / owner timestamp on Continue (and similar). */
    public void onDialogTouched(String dialogId, String trState, byte[] remoteOtid,
                                int remotePc, int remoteSsn) {
        if (dialogId == null || dialogId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        RaDialogOwner owner = localOwners.get(dialogId);
        if (owner == null && clusterCaches != null) {
            owner = clusterCaches.getOwner(dialogId);
        }
        if (owner == null) {
            // First inbound Continue without prior open — claim locally.
            onDialogOpened(dialogId, parseOtid(dialogId), remoteOtid, remotePc, remoteSsn,
                    trState, null);
            return;
        }
        if (localNodeId.equals(owner.ownerNodeId())) {
            RaDialogOwner refreshed = new RaDialogOwner(
                    dialogId, owner.ownerNodeId(), owner.raName(), owner.generation(), now);
            localOwners.put(dialogId, refreshed);
            if (clusterCaches != null) {
                clusterCaches.putOwner(refreshed);
            }
        }
        TcapDialogMeta previous = localMeta.get(dialogId);
        long otid = previous != null ? previous.localOtid() : parseOtid(dialogId);
        byte[] remote = remoteOtid != null ? remoteOtid
                : (previous != null ? previous.remoteOtid() : null);
        int rpc = remotePc != 0 ? remotePc : (previous != null ? previous.remotePc() : 0);
        int rssn = remoteSsn != 0 ? remoteSsn : (previous != null ? previous.remoteSsn() : 0);
        TcapDialogMeta meta = new TcapDialogMeta(
                dialogId, otid, remote, localPc, localSsn, rpc, rssn,
                trState == null ? "Active" : trState, owner.ownerNodeId(), owner.raName(),
                owner.generation(), dialogId, previous != null ? previous.correlationId() : null, now);
        localMeta.put(dialogId, meta);
        if (clusterCaches != null) {
            clusterCaches.putMeta(meta);
        }
    }

    /** End / Abort / idle sweep — remove ownership and meta. */
    public void onDialogClosed(String dialogId) {
        if (dialogId == null || dialogId.isBlank()) {
            return;
        }
        localOwners.remove(dialogId);
        localMeta.remove(dialogId);
        if (clusterCaches != null) {
            clusterCaches.removeOwner(dialogId);
            clusterCaches.removeMeta(dialogId);
        }
        LOG.debug("[ra-jss7] dialog closed id={}", dialogId);
    }

    public Optional<RaDialogOwner> lookupOwner(String dialogId) {
        if (dialogId == null || dialogId.isBlank()) {
            return Optional.empty();
        }
        RaDialogOwner local = localOwners.get(dialogId);
        if (local != null) {
            return Optional.of(local);
        }
        if (clusterCaches != null) {
            RaDialogOwner remote = clusterCaches.getOwner(dialogId);
            if (remote != null) {
                localOwners.put(dialogId, remote);
                return Optional.of(remote);
            }
        }
        return Optional.empty();
    }

    public Optional<TcapDialogMeta> lookupMeta(String dialogId) {
        if (dialogId == null) {
            return Optional.empty();
        }
        TcapDialogMeta local = localMeta.get(dialogId);
        if (local != null) {
            return Optional.of(local);
        }
        if (clusterCaches != null) {
            TcapDialogMeta m = clusterCaches.metaCache().get(dialogId);
            return Optional.ofNullable(m);
        }
        return Optional.empty();
    }

    public void clearAll() {
        localOwners.clear();
        localMeta.clear();
    }

    private static long parseOtid(String dialogId) {
        try {
            return Long.parseLong(dialogId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
