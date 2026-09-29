/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import java.util.function.Supplier;

import com.microjainslee.api.Sbb;

/**
 * JAIN-SLEE 1.1 §8.2 — SBB Entity Pool.
 *
 * <p>
 * Two roles, kept in one type because they were the same type before
 * ADR 0007 D4:
 *
 * <ol>
 * <li><b>Contract</b> that {@link EventRouter} delivers through, so a
 * cluster-aware pool can drive the same hot path. Previously the router held a
 * concrete {@code VirtualThreadSbbEntityPool} which was declared {@code final};
 * {@code DistributedSbbEntityPool} could therefore only compose it, and
 * {@code MicroSleeContainer#bindDistributedSbbPool(Object)} recorded a
 * reference the router never used. The Infinispan {@code sbb-entity-state}
 * cache was written by nobody and read by nobody, and the cross-node hydrate
 * path in {@code DistributedSbbEntityPool#acquire} was unreachable.
 * Extracting the interface is what turns that cache into the substrate for node
 * failover.</li>
 * <li><b>Backward-compatible façade</b> for external code that still imports
 * {@code SbbEntityPool} — retained so legacy callers compile and behave
 * correctly when the container is rebuilt with virtual-thread pooling enabled.
 * It delegates to {@link VirtualThreadSbbEntityPool} so each SBB instance gets
 * its own parked virtual thread, giving the spec-mandated single-threaded event
 * ordering. The original LMAX Disruptor-backed stub never made it into the
 * {@link MicroSleeContainer} registration path.</li>
 * </ol>
 *
 * <h2>Implementations</h2>
 * <ul>
 * <li>{@link VirtualThreadSbbEntityPool} — the local default.</li>
 * <li>{@code com.microjainslee.cluster.DistributedSbbEntityPool} — local-first
 * with replicated {@code @CmpField} snapshots.</li>
 * </ul>
 *
 * <h2>Contract for implementors</h2>
 * <ul>
 * <li>{@link #acquire(String, Supplier)} is idempotent per {@code sbbId}:
 * repeated calls return the <b>same</b> entity, not a fresh one.</li>
 * <li>{@link #release(VirtualThreadSbbEntityPool.SbbEntity)} returns the
 * entity's thread slot for reuse and is safe with an already-released entity.</li>
 * <li>Implementations must be safe for concurrent use.</li>
 * </ul>
 */
public class SbbEntityPool implements SbbEntityPoolContract {

    private final VirtualThreadSbbEntityPool delegate;

    /** Backward-compatible ctor: pool size is mapped to the {@code max} knob. */
    public SbbEntityPool(int poolSize) {
        this(poolSize, poolSize, true);
    }

    public SbbEntityPool(int min, int max, boolean perVirtualThread) {
        this.delegate = new VirtualThreadSbbEntityPool(min, max, perVirtualThread);
    }

    /**
     * Returns the {@link VirtualThreadSbbEntityPool.SbbEntity} for the given
     * SBB ID, materialising the SBB instance via {@code factory} on first call.
     */
    @Override
    public VirtualThreadSbbEntityPool.SbbEntity acquire(String sbbId, Supplier<Sbb> factory) {
        return delegate.acquire(sbbId, factory);
    }

    /** Bind a pre-built instance — used by the cluster pool after a snapshot hydrate. */
    @Override
    public VirtualThreadSbbEntityPool.SbbEntity acquire(String sbbId, long entityId, Sbb sbb) {
        return delegate.acquire(sbbId, entityId, sbb);
    }

    @Override
    public void release(VirtualThreadSbbEntityPool.SbbEntity entity) {
        delegate.release(entity);
    }

    @Override
    public VirtualThreadSbbEntityPool.SbbEntity findEntity(String sbbId) {
        return delegate.findEntity(sbbId);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    public void shutdown() {
        delegate.shutdown();
    }

    /** Expose the underlying VT-backed pool for callers that need its full API. */
    public VirtualThreadSbbEntityPool getDelegate() {
        return delegate;
    }
}
