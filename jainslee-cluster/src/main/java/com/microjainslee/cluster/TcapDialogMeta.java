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

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Serializable TCAP dialog metadata for the {@link Ss7DialogCacheNames#TCAP_DIALOG_META}
 * cache (P0). Not a live jSS7 {@code DialogImpl} — CONTINUE takeover requires a future
 * jSS7 export/import API (ADR 0001 P2).
 *
 * <p>Values must stay within {@link MarshallingAllowList} ({@code com.microjainslee.*}).
 */
public final class TcapDialogMeta implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String dialogKey;
    private final long localOtid;
    private final byte[] remoteOtid;
    private final int localPc;
    private final int localSsn;
    private final int remotePc;
    private final int remoteSsn;
    private final String trState;
    private final String ownerNodeId;
    private final String raName;
    private final long generation;
    private final String activityName;
    private final String correlationId;
    private final long updatedAtEpochMs;

    public TcapDialogMeta(
            String dialogKey,
            long localOtid,
            byte[] remoteOtid,
            int localPc,
            int localSsn,
            int remotePc,
            int remoteSsn,
            String trState,
            String ownerNodeId,
            String raName,
            long generation,
            String activityName,
            String correlationId,
            long updatedAtEpochMs) {
        this.dialogKey = Objects.requireNonNull(dialogKey, "dialogKey");
        this.localOtid = localOtid;
        this.remoteOtid = remoteOtid == null ? null : remoteOtid.clone();
        this.localPc = localPc;
        this.localSsn = localSsn;
        this.remotePc = remotePc;
        this.remoteSsn = remoteSsn;
        this.trState = trState;
        this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        this.raName = raName;
        this.generation = generation;
        this.activityName = activityName;
        this.correlationId = correlationId;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    public String dialogKey() {
        return dialogKey;
    }

    public long localOtid() {
        return localOtid;
    }

    public byte[] remoteOtid() {
        return remoteOtid == null ? null : remoteOtid.clone();
    }

    public int localPc() {
        return localPc;
    }

    public int localSsn() {
        return localSsn;
    }

    public int remotePc() {
        return remotePc;
    }

    public int remoteSsn() {
        return remoteSsn;
    }

    public String trState() {
        return trState;
    }

    public String ownerNodeId() {
        return ownerNodeId;
    }

    public String raName() {
        return raName;
    }

    public long generation() {
        return generation;
    }

    public String activityName() {
        return activityName;
    }

    public String correlationId() {
        return correlationId;
    }

    public long updatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    /** Peer index key for {@link Ss7DialogCacheNames#TCAP_DIALOG_BY_REMOTE}. */
    public String remoteIndexKey() {
        String otidHex = remoteOtid == null ? "-" : bytesToHex(remoteOtid);
        return remotePc + ":" + otidHex;
    }

    public TcapDialogMeta withGeneration(long newGeneration, long updatedAtEpochMs) {
        return new TcapDialogMeta(
                dialogKey, localOtid, remoteOtid, localPc, localSsn, remotePc, remoteSsn,
                trState, ownerNodeId, raName, newGeneration, activityName, correlationId,
                updatedAtEpochMs);
    }

    public TcapDialogMeta withOwner(String newOwnerNodeId, String newRaName, long newGeneration,
                                    long updatedAtEpochMs) {
        return new TcapDialogMeta(
                dialogKey, localOtid, remoteOtid, localPc, localSsn, remotePc, remoteSsn,
                trState, newOwnerNodeId, newRaName, newGeneration, activityName, correlationId,
                updatedAtEpochMs);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TcapDialogMeta that)) {
            return false;
        }
        return localOtid == that.localOtid
                && localPc == that.localPc
                && localSsn == that.localSsn
                && remotePc == that.remotePc
                && remoteSsn == that.remoteSsn
                && generation == that.generation
                && updatedAtEpochMs == that.updatedAtEpochMs
                && dialogKey.equals(that.dialogKey)
                && Arrays.equals(remoteOtid, that.remoteOtid)
                && Objects.equals(trState, that.trState)
                && ownerNodeId.equals(that.ownerNodeId)
                && Objects.equals(raName, that.raName)
                && Objects.equals(activityName, that.activityName)
                && Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(dialogKey, localOtid, localPc, localSsn, remotePc, remoteSsn,
                trState, ownerNodeId, raName, generation, activityName, correlationId,
                updatedAtEpochMs);
        result = 31 * result + Arrays.hashCode(remoteOtid);
        return result;
    }

    @Override
    public String toString() {
        return "TcapDialogMeta[dialogKey=" + dialogKey
                + ", localOtid=" + localOtid
                + ", ownerNodeId=" + ownerNodeId
                + ", generation=" + generation
                + ", trState=" + trState
                + ']';
    }
}
