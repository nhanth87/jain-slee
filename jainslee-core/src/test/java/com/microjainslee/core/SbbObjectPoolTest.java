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

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link SbbObjectPool}.
 *
 * <p>Validates the JCTools MPMC-backed free-list semantics:
 * acquire/release round-trips, factory invocation only on miss,
 * max-cap enforcement, and null-safety.
 */
public class SbbObjectPoolTest {

    private static final class CountingSbb implements Sbb {
        private static final AtomicInteger COUNTER = new AtomicInteger();
        private final int id;
        CountingSbb() { this.id = COUNTER.incrementAndGet(); }
        int getId() { return id; }
    }

    @Test
    public void acquireReturnsNonNull() {
        SbbObjectPool pool = new SbbObjectPool(0, 8, CountingSbb::new);
        Sbb sbb = pool.acquire();
        assertNotNull(sbb);
        assertTrue(sbb instanceof CountingSbb);
    }

    @Test
    public void releaseThenAcquireReusesSameInstance() {
        SbbObjectPool pool = new SbbObjectPool(0, 8, CountingSbb::new);
        Sbb first = pool.acquire();
        pool.release(first);
        Sbb second = pool.acquire();
        assertSame("release()/acquire() must reuse the same instance",
                first, second);
    }

    @Test
    public void factoryOnlyInvokedWhenPoolIsEmpty() {
        AtomicInteger factoryCalls = new AtomicInteger();
        SbbObjectPool pool = new SbbObjectPool(0, 8,
                () -> { factoryCalls.incrementAndGet(); return new CountingSbb(); });
        Sbb first = pool.acquire();
        assertEquals(1, factoryCalls.get());
        pool.release(first);
        pool.acquire(); // must reuse — no factory invocation
        assertEquals("factory must only be called when idle list is empty",
                1, factoryCalls.get());
    }

    @Test
    public void idleCountReflectsReleasedInstances() {
        SbbObjectPool pool = new SbbObjectPool(0, 8, CountingSbb::new);
        Sbb a = pool.acquire();
        Sbb b = pool.acquire();
        assertEquals(0, pool.idleCount());
        pool.release(a);
        pool.release(b);
        assertEquals(2, pool.idleCount());
    }

    @Test
    public void createdCountMatchesTotalCreated() {
        SbbObjectPool pool = new SbbObjectPool(0, 16, CountingSbb::new);
        Sbb a = pool.acquire();
        Sbb b = pool.acquire();
        Sbb c = pool.acquire();
        assertEquals(3, pool.createdCount());
        pool.release(a);
        pool.release(b);
        pool.release(c);
        assertEquals("release must not bump the created counter",
                3, pool.createdCount());
    }

    @Test
    public void maxIsHonored() {
        assertEquals(16, new SbbObjectPool(0, 16, CountingSbb::new).getMax());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroMax() {
        new SbbObjectPool(0, 0, CountingSbb::new);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeMax() {
        new SbbObjectPool(0, -1, CountingSbb::new);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullFactory() {
        new SbbObjectPool(0, 4, null);
    }

    @Test
    public void releaseNullIsNoOp() {
        SbbObjectPool pool = new SbbObjectPool(0, 4, CountingSbb::new);
        pool.release(null); // must not throw
        assertEquals(0, pool.createdCount());
    }

    @Test
    public void concurrentAcquireAndReleaseStaysConsistent() throws Exception {
        // 8 threads × 1000 acquire/release cycles each — sanity test for the
        // JCTools MPMC queue's lock-free semantics.
        final SbbObjectPool pool = new SbbObjectPool(0, 64, CountingSbb::new);
        final int threads = 8;
        final int iters = 1000;
        Thread[] workers = new Thread[threads];
        final AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(new Runnable() {
                @Override public void run() {
                    for (int i = 0; i < iters; i++) {
                        Sbb sbb = pool.acquire();
                        if (sbb == null) {
                            errors.incrementAndGet();
                        }
                        pool.release(sbb);
                    }
                }
            }, "sbb-pool-worker-" + t);
        }
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
        if (errors.get() > 0) {
            fail("acquire() returned null " + errors.get() + " times");
        }
        assertTrue("createdCount should be <= max",
                pool.createdCount() <= pool.getMax());
    }
}