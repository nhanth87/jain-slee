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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link InMemoryCmpFieldStore}.
 */
public class CmpFieldStoreTest {

    @Test
    public void storeAndLoad() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("counter", Integer.valueOf(7));
        state.put("label", "hello");
        store.store("ent-1", state);

        Map<String, Object> loaded = store.load("ent-1");
        assertEquals(2, loaded.size());
        assertEquals(Integer.valueOf(7), loaded.get("counter"));
        assertEquals("hello", loaded.get("label"));
    }

    @Test
    public void loadMissingReturnsEmpty() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        Map<String, Object> loaded = store.load("never-written");
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void removeClearsState() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("k", "v");
        store.store("ent-1", state);
        assertTrue(store.contains("ent-1"));

        store.remove("ent-1");
        assertFalse(store.contains("ent-1"));
        assertTrue(store.load("ent-1").isEmpty());
    }

    @Test
    public void defensiveCopyOnStore() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("counter", Integer.valueOf(0));
        store.store("ent-1", state);

        // Mutate the loaded snapshot — stored state must not change.
        Map<String, Object> loaded = store.load("ent-1");
        loaded.put("counter", Integer.valueOf(99));
        loaded.put("newField", "x");

        Map<String, Object> reloaded = store.load("ent-1");
        assertEquals(Integer.valueOf(0), reloaded.get("counter"));
        assertFalse(reloaded.containsKey("newField"));
        // The load itself returns a fresh map instance each time.
        assertNotSame(loaded, reloaded);
    }

    @Test
    public void concurrentAccessIsThreadSafe() throws Exception {
        final InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        final int threadCount = 10;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        final AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < 100; i++) {
                            Map<String, Object> s = new HashMap<String, Object>();
                            s.put("writer", Integer.valueOf(tid));
                            s.put("iter", Integer.valueOf(i));
                            store.store("shared", s);
                            store.load("shared");
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(0, errors.get());
        assertTrue(store.contains("shared"));
    }
}