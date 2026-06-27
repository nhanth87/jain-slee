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

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link ActivityContextPool}.
 */
public class ActivityContextPoolTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static InMemoryActivityContext factory() {
        return new InMemoryActivityContext("ctx-" + COUNTER.incrementAndGet());
    }

    @Test
    public void acquireReturnsNonNull() {
        ActivityContextPool pool = new ActivityContextPool(0, 8, ActivityContextPoolTest::factory);
        InMemoryActivityContext ctx = pool.acquire();
        assertNotNull(ctx);
    }

    @Test
    public void releaseThenAcquireReusesSameInstance() {
        ActivityContextPool pool = new ActivityContextPool(0, 8, ActivityContextPoolTest::factory);
        InMemoryActivityContext first = pool.acquire();
        pool.release(first);
        InMemoryActivityContext second = pool.acquire();
        assertSame("release()/acquire() must reuse the same instance", first, second);
    }

    @Test
    public void factoryOnlyInvokedWhenPoolIsEmpty() {
        AtomicInteger calls = new AtomicInteger();
        ActivityContextPool pool = new ActivityContextPool(0, 8, () -> {
            calls.incrementAndGet();
            return factory();
        });
        InMemoryActivityContext first = pool.acquire();
        assertEquals(1, calls.get());
        pool.release(first);
        pool.acquire();
        assertEquals("factory must not be called when idle list has an instance",
                1, calls.get());
    }

    @Test
    public void idleCountReflectsReleasedInstances() {
        ActivityContextPool pool = new ActivityContextPool(0, 8, ActivityContextPoolTest::factory);
        InMemoryActivityContext a = pool.acquire();
        InMemoryActivityContext b = pool.acquire();
        assertEquals(0, pool.idleCount());
        pool.release(a);
        pool.release(b);
        assertEquals(2, pool.idleCount());
    }

    @Test
    public void createdCountMatchesTotalCreated() {
        ActivityContextPool pool = new ActivityContextPool(0, 16, ActivityContextPoolTest::factory);
        pool.acquire();
        pool.acquire();
        pool.acquire();
        assertEquals(3, pool.createdCount());
    }

    @Test
    public void releaseNullIsNoOp() {
        ActivityContextPool pool = new ActivityContextPool(0, 4, ActivityContextPoolTest::factory);
        pool.release(null); // must not throw
        assertEquals(0, pool.createdCount());
    }

    @Test
    public void concurrentAcquireAndReleaseStaysConsistent() throws Exception {
        final ActivityContextPool pool = new ActivityContextPool(0, 64,
                ActivityContextPoolTest::factory);
        final int threads = 8;
        final int iters = 1000;
        Thread[] workers = new Thread[threads];
        final AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(new Runnable() {
                @Override public void run() {
                    for (int i = 0; i < iters; i++) {
                        InMemoryActivityContext ctx = pool.acquire();
                        if (ctx == null) errors.incrementAndGet();
                        pool.release(ctx);
                    }
                }
            }, "aci-pool-worker-" + t);
        }
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
        if (errors.get() > 0) {
            fail("acquire() returned null " + errors.get() + " times");
        }
        assertTrue("createdCount should be <= max", pool.createdCount() <= pool.getMax());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroMax() {
        new ActivityContextPool(0, 0, ActivityContextPoolTest::factory);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullFactory() {
        new ActivityContextPool(0, 4, null);
    }
}