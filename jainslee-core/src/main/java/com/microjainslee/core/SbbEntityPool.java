/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.Sbb;

import java.util.function.Supplier;

/**
 * JAIN-SLEE 1.1 §8.2 — SBB Entity Pool (backward-compatible façade).
 *
 * <p>Retained for source compatibility with any external code that still
 * imports {@code SbbEntityPool}. Internally it now delegates to
 * {@link VirtualThreadSbbEntityPool} so each SBB instance gets its own parked
 * virtual thread, giving the spec-mandated single-threaded event ordering.
 *
 * <p>The original LMAX Disruptor-backed stub never made it into the
 * {@link MicroSleeContainer} registration path; the façade exists purely so
 * legacy callers compile and behave correctly when the container is rebuilt
 * with virtual-thread pooling enabled.
 */
public class SbbEntityPool {

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
    public VirtualThreadSbbEntityPool.SbbEntity acquire(String sbbId, Supplier<Sbb> factory) {
        return delegate.acquire(sbbId, factory);
    }

    public void release(VirtualThreadSbbEntityPool.SbbEntity entity) {
        delegate.release(entity);
    }

    public void shutdown() {
        delegate.shutdown();
    }

    /** Expose the underlying VT-backed pool for callers that need its full API. */
    public VirtualThreadSbbEntityPool getDelegate() {
        return delegate;
    }
}
