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

import org.jctools.queues.MpmcArrayQueue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * JAIN-SLEE 1.1 §7 — generic Activity Context Pool.
 *
 * <p>Mirrors {@link SbbObjectPool} for {@link InMemoryActivityContext}
 * instances. Same design rationale:
 *
 * <ul>
 *   <li>JCTools {@link MpmcArrayQueue} → lock-free, no per-acquire
 *       node allocation,</li>
 *   <li>capacity = next-power-of-two of {@code max} so producer /
 *       consumer indexes use bit-mask instead of mod,</li>
 *   <li>overshoot releases drop instances on the floor (eligible for
 *       GC) instead of blocking.</li>
 * </ul>
 *
 * <h2>Why this matters</h2>
 * Activity context creation involves a fair amount of bookkeeping
 * (attached-SBB list, event-type locks, transaction registry entry).
 * Pooling slashes the GC pressure at high call rates — exactly the
 * audit G3 failure mode.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ActivityContextPool {

    private final MpmcArrayQueue<InMemoryActivityContext> idle;
    private final Supplier<InMemoryActivityContext> factory;
    private final int max;
    private final AtomicInteger created;

    /**
     * @param initial unused — kept for symmetry with {@link SbbObjectPool}.
     * @param max     hard cap on live instances; releases that would push
     *                the count above {@code max} drop the instance.
     * @param factory invoked when {@link #acquire()} cannot satisfy from
     *                the idle queue.
     */
    public ActivityContextPool(int initial, int max,
            Supplier<InMemoryActivityContext> factory) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1 (was " + max + ")");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory is required");
        }
        this.max = max;
        this.factory = factory;
        int capacity = Math.max(2, nextPowerOfTwo(max));
        this.idle = new MpmcArrayQueue<InMemoryActivityContext>(capacity);
        this.created = new AtomicInteger();
    }

    /**
     * Acquire an {@link InMemoryActivityContext}, either from the idle
     * pool or by invoking the factory.
     */
    public InMemoryActivityContext acquire() {
        InMemoryActivityContext pooled = idle.poll();
        if (pooled != null) {
            return pooled;
        }
        if (created.get() < max) {
            if (created.incrementAndGet() <= max) {
                return factory.get();
            }
            created.decrementAndGet();
        }
        InMemoryActivityContext extra = factory.get();
        created.incrementAndGet();
        return extra;
    }

    /**
     * Return an instance to the idle pool. If the queue is full the
     * instance is dropped and the create counter is decremented.
     */
    public void release(InMemoryActivityContext ctx) {
        if (ctx == null) {
            return;
        }
        if (!idle.offer(ctx)) {
            created.decrementAndGet();
        }
    }

    /** Number of currently idle instances. Diagnostic only. */
    public int idleCount() {
        return idle.size();
    }

    /** Number of instances ever created. Diagnostic only. */
    public int createdCount() {
        return created.get();
    }

    /** Configured maximum. */
    public int getMax() {
        return max;
    }

    private static int nextPowerOfTwo(int v) {
        if (v <= 1) return 2;
        return Integer.highestOneBit(v - 1) << 1;
    }
}