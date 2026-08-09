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
 * ISPN fence for one SCTP local {@code ip:port} in an n-n RA cluster.
 *
 * <p>Each live RA node claims exactly one endpoint at steady state. When a
 * peer leaves the Infinispan view, a survivor may CAS-claim the orphaned
 * endpoint (VIP / migrate bind) with a bumped {@link #generation()}.
 */
public final class SctpEndpointLease implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String endpointKey;
    private final String ownerNodeId;
    private final long generation;
    private final long updatedAtEpochMs;

    public SctpEndpointLease(
            String endpointKey,
            String ownerNodeId,
            long generation,
            long updatedAtEpochMs) {
        this.endpointKey = Objects.requireNonNull(endpointKey, "endpointKey");
        this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        this.generation = generation;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    public String endpointKey() {
        return endpointKey;
    }

    public String ownerNodeId() {
        return ownerNodeId;
    }

    public long generation() {
        return generation;
    }

    public long updatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public SctpEndpointLease withOwner(String newOwnerNodeId, long newGeneration, long updatedAtEpochMs) {
        return new SctpEndpointLease(endpointKey, newOwnerNodeId, newGeneration, updatedAtEpochMs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SctpEndpointLease that)) {
            return false;
        }
        return generation == that.generation
                && updatedAtEpochMs == that.updatedAtEpochMs
                && endpointKey.equals(that.endpointKey)
                && ownerNodeId.equals(that.ownerNodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpointKey, ownerNodeId, generation, updatedAtEpochMs);
    }

    @Override
    public String toString() {
        return "SctpEndpointLease[endpoint=" + endpointKey
                + ", ownerNodeId=" + ownerNodeId
                + ", generation=" + generation
                + ']';
    }
}
