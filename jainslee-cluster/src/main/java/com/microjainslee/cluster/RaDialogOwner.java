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
import java.util.Objects;

/**
 * Sticky outbound ownership for a SLEE dialog activity id
 * ({@link Ss7DialogCacheNames#RA_DIALOG_OWNER}).
 *
 * <p>{@link #generation()} is a monotonic fence: CAS replace must bump generation
 * so a stale node cannot send CONTINUE after a successful failover claim (P1+).
 */
public final class RaDialogOwner implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String dialogId;
    private final String ownerNodeId;
    private final String raName;
    private final long generation;
    private final long updatedAtEpochMs;

    public RaDialogOwner(
            String dialogId,
            String ownerNodeId,
            String raName,
            long generation,
            long updatedAtEpochMs) {
        this.dialogId = Objects.requireNonNull(dialogId, "dialogId");
        this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        this.raName = raName;
        this.generation = generation;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    public String dialogId() {
        return dialogId;
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

    public long updatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public RaDialogOwner withOwner(String newOwnerNodeId, String newRaName, long newGeneration,
                                   long updatedAtEpochMs) {
        return new RaDialogOwner(dialogId, newOwnerNodeId, newRaName, newGeneration, updatedAtEpochMs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RaDialogOwner that)) {
            return false;
        }
        return generation == that.generation
                && updatedAtEpochMs == that.updatedAtEpochMs
                && dialogId.equals(that.dialogId)
                && ownerNodeId.equals(that.ownerNodeId)
                && Objects.equals(raName, that.raName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dialogId, ownerNodeId, raName, generation, updatedAtEpochMs);
    }

    @Override
    public String toString() {
        return "RaDialogOwner[dialogId=" + dialogId
                + ", ownerNodeId=" + ownerNodeId
                + ", raName=" + raName
                + ", generation=" + generation
                + ']';
    }
}
