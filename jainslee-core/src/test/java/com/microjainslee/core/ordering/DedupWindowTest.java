/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.ordering;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Sprint S8 - DedupWindow tests.
 *
 * <p>Covers:</p>
 * <ol>
 *   <li>isDuplicate returns false the first time, true the second.</li>
 *   <li>Window expiry: after windowMs the same key is treated as new.</li>
 *   <li>LRU cap: when the cap is exceeded the eldest entry is evicted.</li>
 *   <li>Multi-convergence isolation: same seqNum under different keys.</li>
 *   <li>Counter + diagnostic stability.</li>
 * </ol>
 */
public class DedupWindowTest {

    private DedupWindow win;

    @Before
    public void setUp() {
        win = new DedupWindow(1_000L, 128);
    }

    // 1. isDuplicate true on second sighting inside the window.
    @Test
    public void isDuplicate_secondHitInsideWindowReturnsTrue() {
        long now = 100_000L;
        assertFalse("first hit is new", win.isDuplicate("s1", 1L, now));
        assertTrue("second hit inside window is duplicate", win.isDuplicate("s1", 1L, now + 100L));
        assertEquals("dedupHitCount reflects 1 hit", 1L, win.getDedupHitCount());
        assertEquals("admitCount reflects 1 new entry", 1L, win.getAdmitCount());
    }

    // 2. Window expiry: same key after expiry is treated as new again.
    @Test
    public void windowExpiry_sameKeyAfterWindowIsNew() {
        long now = 100_000L;
        assertFalse(win.isDuplicate("s2", 42L, now));
        // jump past expiry (windowMs = 1_000).
        assertFalse("past the window, key is fresh", win.isDuplicate("s2", 42L, now + 1_500L));
        assertEquals(0L, win.getDedupHitCount());
        assertEquals("admitted twice across the window", 2L, win.getAdmitCount());
        // next hit is now a duplicate (a fresh window is open).
        assertTrue(win.isDuplicate("s2", 42L, now + 1_600L));
        assertEquals(1L, win.getDedupHitCount());
    }

    // 3. evictExpired removes stale entries and frees LRU slots.
    @Test
    public void evictExpired_removesStaleEntries() {
        long now = 100_000L;
        win.isDuplicate("a", 1L, now);
        win.isDuplicate("b", 1L, now);
        win.isDuplicate("c", 1L, now);
        assertEquals(3, win.size());
        // jump past expiry
        int removed = win.evictExpired(now + 2_000L);
        assertEquals(3, removed);
        assertEquals(0, win.size());
        // All three keys are reusable now.
        assertFalse(win.isDuplicate("a", 1L, now + 2_500L));
        assertFalse(win.isDuplicate("b", 1L, now + 2_500L));
        assertFalse(win.isDuplicate("c", 1L, now + 2_500L));
        assertEquals(3, win.size());
    }

    // 4. LRU cap: with cap=N, admitting N+1 entries evicts the eldest.
    @Test
    public void lruCap_evictsEldestOnOverflow() {
        DedupWindow small = new DedupWindow(60_000L, 4);
        long now = 100_000L;
        for (long seq = 1; seq <= 4; seq++) {
            assertFalse(small.isDuplicate("L", seq, now));
        }
        assertEquals(4, small.size());
        // 5th admission evicts the eldest (seq=1).
        assertFalse(small.isDuplicate("L", 5L, now));
        // Map size must remain at the cap (eldest evicted).
        assertEquals(4, small.size());
        // Oldest seq is gone -> admitting seq=1 again is NOT a duplicate.
        assertFalse(small.isDuplicate("L", 1L, now + 1L));
        assertEquals("admitCount counts every admission including after eviction",
                6L, small.getAdmitCount());
    }

    // 5. Multi-convergence: same seqNum under different keys is distinct.
    @Test
    public void multiConvergence_sameSeqDifferentKeys() {
        long now = 100_000L;
        assertFalse(win.isDuplicate("alice", 1L, now));
        assertFalse(win.isDuplicate("bob", 1L, now));
        assertFalse(win.isDuplicate("carol", 1L, now));
        assertEquals(3, win.size());
        assertEquals("3 admits, 0 hits", 3L, win.getAdmitCount());

        // Second hits are duplicates within their own convergence.
        assertTrue(win.isDuplicate("alice", 1L, now + 10L));
        assertTrue(win.isDuplicate("bob", 1L, now + 10L));
        assertFalse(win.isDuplicate("carol", 2L, now + 10L)); // new seq
        assertEquals(2L, win.getDedupHitCount());
        assertEquals(4L, win.getAdmitCount());

        // purgeConvergence frees alice's slot; bob/carol unaffected.
        // (Note: carol appears with both seq=1 and seq=2; both should
        // remain because they share the "carol" convergence key.)
        int purged = win.purgeConvergence("alice");
        assertEquals(1, purged);
        assertEquals(3, win.size());
        assertFalse(win.isDuplicate("alice", 1L, now + 20L)); // fresh window for alice
        // bob seq=1 still a duplicate.
        assertTrue(win.isDuplicate("bob", 1L, now + 20L));
        // carol seq=2 still a duplicate.
        assertTrue(win.isDuplicate("carol", 2L, now + 20L));
    }

    // 6. Constructor validation rejects bad args.
    @Test
    public void invalidConstructorArgs_throwIae() {
        try {
            new DedupWindow(0L, 16);
            fail("expected IAE for windowMs=0");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new DedupWindow(1_000L, 0);
            fail("expected IAE for maxEntries=0");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // 7. Bad inputs are admitted (never reported as duplicates).
    @Test
    public void badInputs_areNeverDuplicates() {
        long now = 100_000L;
        assertFalse("null conv -> not a duplicate",
                win.isDuplicate(null, 1L, now));
        assertFalse("empty conv -> not a duplicate",
                win.isDuplicate("", 1L, now));
        assertFalse("seq=0 -> not a duplicate",
                win.isDuplicate("ok", 0L, now));
        assertFalse("seq<0 -> not a duplicate",
                win.isDuplicate("ok", -5L, now));
    }

    // 8. Default constants match the Sprint S8 spec.
    @Test
    public void defaults_matchSpec() {
        DedupWindow d = new DedupWindow();
        assertEquals(300_000L, d.getWindowMs());
        assertEquals(65_536, d.getMaxEntries());
        assertEquals(DedupWindow.DEFAULT_WINDOW_MS, d.getWindowMs());
        assertEquals(DedupWindow.DEFAULT_MAX_ENTRIES, d.getMaxEntries());
    }

    // 9. clear() resets state and counters.
    @Test
    public void clear_resetsState() {
        long now = 100_000L;
        win.isDuplicate("x", 1L, now);
        win.isDuplicate("x", 1L, now + 1L);
        assertTrue(win.size() > 0);
        assertNotEquals(0L, win.getDedupHitCount());
        win.clear();
        assertEquals(0, win.size());
        assertEquals(0L, win.getDedupHitCount());
        assertEquals(0L, win.getAdmitCount());
    }

    // 10. evictExpired(0) is a no-op (everything is in the future).
    @Test
    public void evictExpired_nowZero_noOp() {
        long now = 100_000L;
        win.isDuplicate("k", 1L, now);
        assertEquals(0, win.evictExpired(0L));
        assertEquals(1, win.size());
    }

    // 11. Sanity - LRU is access-order: touching an entry protects it.
    @Test
    public void lruIsAccessOrdered_touchProtects() {
        DedupWindow small = new DedupWindow(60_000L, 3);
        long now = 100_000L;
        small.isDuplicate("k", 1L, now); // 1
        small.isDuplicate("k", 2L, now); // 1, 2
        small.isDuplicate("k", 3L, now); // 1, 2, 3
        // Touch seq=1 to move it to the back.
        assertTrue(small.isDuplicate("k", 1L, now + 1L));
        // Admit seq=4 -> eldest non-recent (seq=2) gets evicted.
        assertFalse(small.isDuplicate("k", 4L, now + 2L));
        // seq=2 is fresh now (it was evicted).
        assertFalse(small.isDuplicate("k", 2L, now + 3L));
        // seq=1 is still a duplicate (it was touched, never evicted).
        assertTrue(small.isDuplicate("k", 1L, now + 4L));
    }
}
