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
 * ADR 0007 D3 — a <b>leased</b> successor to {@link RaDialogOwner}.
 *
 * <h2>Why the plain owner record was not enough</h2>
 * {@code RaDialogOwner} had no TTL, no {@code lifespan()}, no reaper and no
 * liveness check. A node that died holding {@code ra-dialog-owner[gmlc-x]} left
 * that row naming a dead node <em>forever</em>, so every surviving node resolved
 * {@code FORWARD_REMOTE} (or {@code REJECT} under the sync-path default) for
 * every subsequent message on that dialog. Orphaned, never reclaimed.
 *
 * <p>
 * The one mechanism that already worked is
 * {@code SctpEndpointFailoverCoordinator#reclaimOrphanedEndpoints()}: an
 * Infinispan {@code @ViewChanged} listener, {@code isNodePresent(owner)}, then
 * a CAS with {@code generation + 1}. This record generalises that pattern to
 * every protocol dialog.
 *
 * <h2>Lease semantics</h2>
 * <ul>
 * <li>{@link #leaseExpiresAtEpochMs()} — wall-clock deadline. A dialog that sits
 * idle for 60s is <b>normal</b> (TCAP dialog idle timeout is 60s by default), so
 * the lease must be renewed by a heartbeat, not only by traffic. Otherwise every
 * quiet dialog would be stolen from its live owner.</li>
 * <li>{@link #ownerBootEpochMs()} — fencing. Bumped on every JVM start. A node
 * that has been dead long enough to be reclaimed and later comes back must not be
 * able to resume ownership just because its node id is in the view again: its
 * stale in-flight writes carry an old boot epoch.</li>
 * <li>{@link #generation()} — monotonic CAS fence, as before.</li>
 * </ul>
 *
 * <p>
 * <b>Not enforced on the wire by itself.</b> The generation/boot-epoch check
 * must also be applied immediately before transmit (ADR 0007 D3 "enforce on the
 * send path") — a lease record alone cannot stop a partitioned-but-alive zombie
 * from sending.
 */
public final class RaDialogLease implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String activityId;
    private final String ownerNodeId;
    private final String raName;
    private final long generation;
    private final long ownerBootEpochMs;
    private final long leaseExpiresAtEpochMs;
    private final long updatedAtEpochMs;

    public RaDialogLease(
            String activityId,
            String ownerNodeId,
            String raName,
            long generation,
            long ownerBootEpochMs,
            long leaseExpiresAtEpochMs,
            long updatedAtEpochMs) {
        this.activityId = Objects.requireNonNull(activityId, "activityId");
        this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        this.raName = raName;
        this.generation = generation;
        this.ownerBootEpochMs = ownerBootEpochMs;
        this.leaseExpiresAtEpochMs = leaseExpiresAtEpochMs;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    /**
     * @param leaseTtlMs lease duration; must exceed the owner's heartbeat period
     *                   with margin, otherwise a GC pause causes spurious steals
     */
    public static RaDialogLease claim(
            String activityId, String ownerNodeId, String raName,
            long bootEpochMs, long nowEpochMs, long leaseTtlMs) {
        return new RaDialogLease(activityId, ownerNodeId, raName, 0L, bootEpochMs,
                nowEpochMs + Math.max(leaseTtlMs, 1_000L), nowEpochMs);
    }

    public String activityId() {
        return activityId;
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

    /** Startup time of the owning JVM; distinguishes incarnations of one node id. */
    public long ownerBootEpochMs() {
        return ownerBootEpochMs;
    }

    public long leaseExpiresAtEpochMs() {
        return leaseExpiresAtEpochMs;
    }

    public long updatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public boolean isExpired(long nowEpochMs) {
        return nowEpochMs >= leaseExpiresAtEpochMs;
    }

    /**
     * Renew without changing ownership or generation. A heartbeat uses this so a
     * quiet-but-healthy owner is not mistaken for a dead one.
     */
    public RaDialogLease renewed(long nowEpochMs, long leaseTtlMs) {
        return new RaDialogLease(activityId, ownerNodeId, raName, generation, ownerBootEpochMs,
                nowEpochMs + Math.max(leaseTtlMs, 1_000L), nowEpochMs);
    }

    /**
     * Take over after a lease expired or its owner left the view. Bumps BOTH the
     * generation (CAS fence) and the boot epoch (incarnation fence).
     */
    public RaDialogLease reclaimedBy(String newOwnerNodeId, String newRaName, long newBootEpochMs,
                                     long nowEpochMs, long leaseTtlMs) {
        return new RaDialogLease(activityId, newOwnerNodeId, newRaName, generation + 1, newBootEpochMs,
                nowEpochMs + Math.max(leaseTtlMs, 1_000L), nowEpochMs);
    }

    /**
     * ADR 0007 D3 — a sender holding this view must re-verify ownership with a CAS
     * immediately before transmitting. Returns {@code false} when the record no
     * longer reflects the sender's belief (someone reclaimed it), which is the
     * signal to abort rather than send.
     */
    public boolean stillOwnedBy(String candidateNodeId, long candidateBootEpochMs) {
        return ownerNodeId.equals(candidateNodeId) && ownerBootEpochMs == candidateBootEpochMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RaDialogLease that)) {
            return false;
        }
        return generation == that.generation
                && ownerBootEpochMs == that.ownerBootEpochMs
                && leaseExpiresAtEpochMs == that.leaseExpiresAtEpochMs
                && updatedAtEpochMs == that.updatedAtEpochMs
                && activityId.equals(that.activityId)
                && ownerNodeId.equals(that.ownerNodeId)
                && Objects.equals(raName, that.raName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(activityId, ownerNodeId, raName, generation, ownerBootEpochMs,
                leaseExpiresAtEpochMs, updatedAtEpochMs);
    }

    @Override
    public String toString() {
        return "RaDialogLease[activityId=" + activityId
                + ", ownerNodeId=" + ownerNodeId
                + ", raName=" + raName
                + ", generation=" + generation
                + ", bootEpochMs=" + ownerBootEpochMs
                + ", expiresAtMs=" + leaseExpiresAtEpochMs
                + ']';
    }
}
