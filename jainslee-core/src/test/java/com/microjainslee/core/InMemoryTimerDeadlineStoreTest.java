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

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Single-JVM {@link TimerDeadlineStore} behaviour reference (ADR 0007 D6).
 *
 * <p>The replicated cluster store must match these semantics; this class pins
 * the local contract: absolute-deadline put/get/remove, overwrite by timer id,
 * and a concurrent put path that loses no keys.
 */
public class InMemoryTimerDeadlineStoreTest {

    private static final String ACI = "otid:42";

    private final InMemoryTimerDeadlineStore store = new InMemoryTimerDeadlineStore();

    private static TimerDeadlineStore.TimerDeadline deadline(long timerId, long expiresAtEpochMs) {
        return new TimerDeadlineStore.TimerDeadline(timerId, ACI, expiresAtEpochMs, timerId);
    }

    @Test
    public void putGetRemoveAndSize() {
        assertEquals(0, store.size());
        assertNull(store.get(1L));

        store.put(deadline(1L, 1_000L));
        assertEquals(1, store.size());
        TimerDeadlineStore.TimerDeadline seen = store.get(1L);
        assertNotNull(seen);
        assertEquals(1L, seen.timerId());
        assertEquals(ACI, seen.activityContextName());
        assertEquals(1_000L, seen.expiresAtEpochMs());
        assertEquals(1L, seen.ownerId());

        store.put(deadline(2L, 2_000L));
        assertEquals(2, store.size());

        store.remove(1L);
        assertEquals(1, store.size());
        assertNull(store.get(1L));
        assertNotNull(store.get(2L));

        store.remove(2L);
        assertEquals(0, store.size());
    }

    @Test
    public void putSameTimerIdOverwrites() {
        store.put(deadline(7L, 1_000L));
        store.put(new TimerDeadlineStore.TimerDeadline(7L, "gmlc-1", 5_000L, 9L));

        assertEquals("overwrite must not grow the store", 1, store.size());
        TimerDeadlineStore.TimerDeadline seen = store.get(7L);
        assertNotNull(seen);
        assertEquals("gmlc-1", seen.activityContextName());
        assertEquals(5_000L, seen.expiresAtEpochMs());
        assertEquals(9L, seen.ownerId());
    }

    @Test
    public void getMissingReturnsNull() {
        assertNull(store.get(404L));
        store.put(deadline(1L, 1_000L));
        assertNull("only the tracked id is visible", store.get(2L));
    }

    @Test
    public void removeMissingIsNoOp() {
        store.remove(999L);
        assertEquals(0, store.size());

        store.put(deadline(1L, 1_000L));
        store.remove(999L);
        assertEquals("removing an unknown id must not disturb live entries", 1, store.size());
        assertNotNull(store.get(1L));
    }

    @Test
    public void putNullDeadlineThrowsNullPointerException() {
        try {
            store.put(null);
            fail("null deadline must be rejected");
        } catch (NullPointerException expected) {
            // ConcurrentHashMap rejects null keys/values — fail closed.
        }
        assertEquals(0, store.size());
    }

    @Test
    public void concurrentPutsFromManyThreadsLoseNoKeys() throws Exception {
        final int threads = 8;
        final int perThread = 500;
        final int expected = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        try {
            for (int t = 0; t < threads; t++) {
                final int base = t * perThread;
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int i = 0; i < perThread; i++) {
                            long id = (long) base + i + 1L;
                            store.put(deadline(id, id));
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(expected, store.size());
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            for (int i = 0; i < perThread; i++) {
                long id = (long) base + i + 1L;
                TimerDeadlineStore.TimerDeadline seen = store.get(id);
                assertNotNull("lost key " + id, seen);
                assertEquals(id, seen.timerId());
            }
        }
    }
}
