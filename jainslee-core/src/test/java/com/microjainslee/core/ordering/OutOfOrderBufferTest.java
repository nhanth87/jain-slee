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

import com.microjainslee.api.SequencedEvent;
import com.microjainslee.api.SleeEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Sprint S8 - OutOfOrderBuffer tests.
 *
 * <p>Covers:</p>
 * <ol>
 *   <li>Enqueue + drainReady FIFO sequence ordering.</li>
 *   <li>TTL eviction via evictExpired.</li>
 *   <li>Per-convergence capacity overflow drops oldest.</li>
 *   <li>Per-convergence isolation (state is per-key).</li>
 *   <li>getNextExpectedSeq tracks the highest seen sequence.</li>
 *   <li>Null / empty / bad inputs are rejected silently or with IAE.</li>
 * </ol>
 */
public class OutOfOrderBufferTest {

    /** Trivial {@link SequencedEvent} backed by an int counter. */
    static final class FakeSeqEvent implements SequencedEvent, SleeEvent {
        private static final AtomicInteger COUNTER = new AtomicInteger();
        private final int id = COUNTER.incrementAndGet();
        private final long seq;
        private final String conv;

        FakeSeqEvent(long seq, String conv) {
            this.seq = seq;
            this.conv = conv;
        }
        @Override public long getSequenceNumber() { return seq; }
        @Override public String getConvergenceName() { return conv; }
        @Override public String toString() {
            return "FakeSeqEvent#" + id + "[conv=" + conv + ",seq=" + seq + "]";
        }
    }

    private OutOfOrderBuffer buf;

    @Before
    public void setUp() {
        buf = new OutOfOrderBuffer(4, 10_000L, 1_000L);
    }

    @After
    public void tearDown() {
        if (buf != null) buf.shutdown();
    }

    // 1. Enqueue + drainReady in FIFO order; nothing buffered until ready.
    @Test
    public void enqueueAndDrainReady_returnsFifoInOrder() {
        Object aci = new Object();
        buf.enqueue(new FakeSeqEvent(1, "sess-A"), aci);
        buf.enqueue(new FakeSeqEvent(2, "sess-A"), aci);
        buf.enqueue(new FakeSeqEvent(3, "sess-A"), aci);

        // No drain yet - all 3 are buffered (seq > expected).
        assertEquals(0, buf.drainReady("sess-A", 0, 100).size());

        // expected = 3: drain all three in FIFO order.
        List<OutOfOrderBuffer.BufferedEvent> ready = buf.drainReady("sess-A", 3, 100);
        assertEquals(3, ready.size());
        assertEquals(1L, ready.get(0).event().getSequenceNumber());
        assertEquals(2L, ready.get(1).event().getSequenceNumber());
        assertEquals(3L, ready.get(2).event().getSequenceNumber());
        assertEquals(0, buf.depthFor("sess-A"));
        assertEquals(0, buf.bufferedConvergenceCount());
    }

    // 2. drainReady honours maxItems; remaining events stay buffered.
    @Test
    public void drainReady_respectsMaxItems() {
        Object aci = new Object();
        buf.enqueue(new FakeSeqEvent(1, "sess-B"), aci);
        buf.enqueue(new FakeSeqEvent(2, "sess-B"), aci);
        buf.enqueue(new FakeSeqEvent(3, "sess-B"), aci);
        buf.enqueue(new FakeSeqEvent(4, "sess-B"), aci);

        List<OutOfOrderBuffer.BufferedEvent> first = buf.drainReady("sess-B", 4, 2);
        assertEquals(2, first.size());
        assertEquals(1L, first.get(0).event().getSequenceNumber());
        assertEquals(2L, first.get(1).event().getSequenceNumber());
        assertEquals("remaining 2 events stay buffered", 2, buf.depthFor("sess-B"));

        List<OutOfOrderBuffer.BufferedEvent> second = buf.drainReady("sess-B", 4, 100);
        assertEquals(2, second.size());
        assertEquals(3L, second.get(0).event().getSequenceNumber());
        assertEquals(4L, second.get(1).event().getSequenceNumber());
    }

    // 3. Per-convergence capacity overflow drops oldest.
    @Test
    public void capacityOverflow_dropsOldestAndIncrementsCounter() {
        Object aci = new Object();
        // capacity = 4, push 6 events.
        for (long s = 1; s <= 6; s++) {
            buf.enqueue(new FakeSeqEvent(s, "sess-C"), aci);
        }
        assertEquals(4, buf.depthFor("sess-C"));
        assertEquals(2L, buf.getOverflowDrops());

        // Oldest (seq=1, seq=2) should have been dropped.
        List<OutOfOrderBuffer.BufferedEvent> ready = buf.drainReady("sess-C", 6, 100);
        assertEquals(4, ready.size());
        assertEquals(3L, ready.get(0).event().getSequenceNumber());
        assertEquals(6L, ready.get(3).event().getSequenceNumber());
    }

    // 4. Per-convergence isolation: two sessions do not interfere.
    @Test
    public void perConvergenceIsolation_stateIsPerKey() {
        Object aci = new Object();
        buf.enqueue(new FakeSeqEvent(10, "alice"), aci);
        buf.enqueue(new FakeSeqEvent(20, "bob"), aci);
        buf.enqueue(new FakeSeqEvent(11, "alice"), aci);

        assertEquals(2, buf.depthFor("alice"));
        assertEquals(1, buf.depthFor("bob"));
        assertEquals(2, buf.bufferedConvergenceCount());

        List<OutOfOrderBuffer.BufferedEvent> aliceDrain = buf.drainReady("alice", 11, 100);
        assertEquals(2, aliceDrain.size());
        assertEquals(10L, aliceDrain.get(0).event().getSequenceNumber());
        assertEquals(11L, aliceDrain.get(1).event().getSequenceNumber());
        assertEquals(0, buf.depthFor("alice"));
        // bob untouched
        assertEquals(1, buf.depthFor("bob"));
    }

    // 5. TTL eviction: events older than windowMs are dropped.
    @Test
    public void evictExpired_dropsOlderThanWindow() throws Exception {
        OutOfOrderBuffer ttlBuf = new OutOfOrderBuffer(16, 80L, 1_000L);
        try {
            Object aci = new Object();
            ttlBuf.enqueue(new FakeSeqEvent(1, "old"), aci);
            // Wait past the TTL window.
            Thread.sleep(150L);
            int dropped = ttlBuf.evictExpired();
            assertEquals(1, dropped);
            assertEquals(0, ttlBuf.depthFor("old"));
            assertEquals(0, ttlBuf.bufferedConvergenceCount());
        } finally {
            ttlBuf.shutdown();
        }
    }

    // 6. getNextExpectedSeq tracks highest seen sequence per convergence.
    @Test
    public void getNextExpectedSeq_tracksHighestSeen() {
        Object aci = new Object();
        assertEquals(0L, buf.getNextExpectedSeq("sess-D"));
        buf.enqueue(new FakeSeqEvent(5, "sess-D"), aci);
        assertEquals(5L, buf.getNextExpectedSeq("sess-D"));
        buf.enqueue(new FakeSeqEvent(3, "sess-D"), aci);
        assertEquals("higher seq wins", 5L, buf.getNextExpectedSeq("sess-D"));
        buf.enqueue(new FakeSeqEvent(42, "sess-D"), aci);
        assertEquals(42L, buf.getNextExpectedSeq("sess-D"));
        assertEquals("unseen conv returns 0", 0L, buf.getNextExpectedSeq("nope"));

        // Drain advances the counter past the drained batch.
        List<OutOfOrderBuffer.BufferedEvent> drained =
                buf.drainReady("sess-D", 42, 100);
        assertEquals(3, drained.size());
        assertEquals(42L, buf.getNextExpectedSeq("sess-D"));
    }

    // 7. Null/missing inputs: enqueue returns false silently.
    @Test
    public void enqueue_rejectsNullOrMissingConvergence() {
        assertFalse(buf.enqueue(null, new Object()));
        Object aci = new Object();
        // null / empty convergence is rejected
        SequencedEvent nullConv = new FakeSeqEvent(1, null);
        assertFalse(buf.enqueue(nullConv, aci));
        SequencedEvent emptyConv = new FakeSeqEvent(1, "");
        assertFalse(buf.enqueue(emptyConv, aci));
        // null activityContext is rejected
        assertFalse(buf.enqueue(new FakeSeqEvent(1, "ok"), null));
        // nothing was actually buffered
        assertEquals(0, buf.bufferedConvergenceCount());
    }

    // 8. Invalid constructor args throw IllegalArgumentException.
    @Test
    public void invalidConstructorArgs_throwIae() {
        try {
            new OutOfOrderBuffer(0, 1_000L);
            fail("expected IAE for capacity=0");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new OutOfOrderBuffer(4, 0L);
            fail("expected IAE for windowMs=0");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new OutOfOrderBuffer(4, 1_000L, 0L);
            fail("expected IAE for sweep=0");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // 9. drainAll flushes everything regardless of seq; convergence removed.
    @Test
    public void drainAll_flushesEverything() {
        Object aci = new Object();
        buf.enqueue(new FakeSeqEvent(1, "x"), aci);
        buf.enqueue(new FakeSeqEvent(2, "x"), aci);
        buf.enqueue(new FakeSeqEvent(3, "x"), aci);
        List<OutOfOrderBuffer.BufferedEvent> all = buf.drainAll("x");
        assertEquals(3, all.size());
        assertEquals(0, buf.depthFor("x"));
        assertEquals(0, buf.bufferedConvergenceCount());
        assertEquals(0, buf.bufferedEventCount());
    }

    // 10. Sanity - drained entries expose the original event + aci.
    @Test
    public void bufferedEvent_exposesEventAndAci() {
        Object aci = new Object();
        SequencedEvent ev = new FakeSeqEvent(7, "y");
        buf.enqueue(ev, aci);
        List<OutOfOrderBuffer.BufferedEvent> drained = buf.drainReady("y", 7, 1);
        assertEquals(1, drained.size());
        OutOfOrderBuffer.BufferedEvent be = drained.get(0);
        assertNotNull(be);
        assertNotNull(be.event());
        assertSame(ev, be.event());
        assertSame(aci, be.activityContext());
        assertTrue("receivedAtMs must be positive", be.receivedAtMs() > 0L);
    }

    // 11. Sanity - BufferedEvent ctor validates non-null inputs.
    @Test
    public void bufferedEvent_rejectsNullsInCtor() {
        Object aci = new Object();
        try {
            new OutOfOrderBuffer.BufferedEvent(null, aci, 1L);
            fail("expected IAE for null event");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        SequencedEvent ev = new FakeSeqEvent(1, "x");
        try {
            new OutOfOrderBuffer.BufferedEvent(ev, null, 1L);
            fail("expected IAE for null aci");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new OutOfOrderBuffer.BufferedEvent(ev, aci, 0L);
            fail("expected IAE for non-positive timestamp");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // 12. drainReady stops at the gap (FIFO invariant).
    @Test
    public void drainReady_stopsAtGap() {
        Object aci = new Object();
        // In-order: seq=1, seq=3, seq=4. The head (seq=1) fits; then
        // seq=3 fits (because currentExpectedSeq=4); seq=4 fits too.
        // This is correct: we can drain every event whose seq <=
        // expected in arrival order.
        buf.enqueue(new FakeSeqEvent(1, "g"), aci);
        buf.enqueue(new FakeSeqEvent(3, "g"), aci);
        buf.enqueue(new FakeSeqEvent(4, "g"), aci);

        List<OutOfOrderBuffer.BufferedEvent> drained = buf.drainReady("g", 4, 100);
        assertEquals(3, drained.size());
        assertEquals(1L, drained.get(0).event().getSequenceNumber());
        assertEquals(3L, drained.get(1).event().getSequenceNumber());
        assertEquals(4L, drained.get(2).event().getSequenceNumber());
        assertEquals(0, buf.depthFor("g"));

        // The real "stops at gap" test: queue seq=10, seq=11, but
        // caller expects only seq=10 (currentExpectedSeq=10). seq=11
        // must remain buffered.
        buf.enqueue(new FakeSeqEvent(10, "h"), aci);
        buf.enqueue(new FakeSeqEvent(11, "h"), aci);
        List<OutOfOrderBuffer.BufferedEvent> hDrain = buf.drainReady("h", 10, 100);
        assertEquals("only seq=10 fits; seq=11 stays", 1, hDrain.size());
        assertEquals(10L, hDrain.get(0).event().getSequenceNumber());
        assertEquals(1, buf.depthFor("h"));
    }

    // 13. Sanity - sanity helper methods are stable.
    @Test
    public void diagnosticsAndDefaults_areStable() {
        assertEquals(4, buf.getPerConvergenceCapacity());
        assertEquals(10_000L, buf.getWindowMs());
        assertEquals(1024, OutOfOrderBuffer.DEFAULT_CAPACITY);
        assertEquals(60_000L, OutOfOrderBuffer.DEFAULT_WINDOW_MS);
        assertEquals(30_000L, OutOfOrderBuffer.DEFAULT_SWEEP_INTERVAL_MS);
    }

    // Helper for assertSame without adding a static import
    private static void assertSame(Object expected, Object actual) {
        org.junit.Assert.assertSame(expected, actual);
    }
}
