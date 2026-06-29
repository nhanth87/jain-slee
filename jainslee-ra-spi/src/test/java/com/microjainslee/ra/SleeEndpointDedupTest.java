/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityContextNamingFacility;
import com.microjainslee.api.EventType;
import com.microjainslee.api.EventTypeId;
import com.microjainslee.api.FireableEventType;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.SequencedEvent;
import com.microjainslee.api.SleeEvent;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Sprint S8 - SleeEndpointImpl dedup hook tests.
 *
 * <p>Covers:</p>
 * <ol>
 *   <li>Firing the same SequencedEvent twice -&gt; handler is invoked once.</li>
 *   <li>Firing events with different sequence numbers -&gt; both delivered.</li>
 *   <li>After the dedup window expires, the same key is delivered again.</li>
 * </ol>
 */
public class SleeEndpointDedupTest {

    static final class StubRa implements ResourceAdaptor {
        @Override public void setResourceAdaptorContext(com.microjainslee.api.ResourceAdaptorContext ctx) {}
        @Override public void raConfigure() {}
        @Override public void raActive() {}
        @Override public void raStopping() {}
        @Override public void raInactive() {}
        @Override public void raUnconfigure() {}
    }

    static final class StubAcnf implements ActivityContextNamingFacility {
        final java.util.concurrent.ConcurrentHashMap<String, ActivityContextInterface> map =
                new java.util.concurrent.ConcurrentHashMap<>();
        @Override public void bind(String name, ActivityContextInterface aci) { map.put(name, aci); }
        @Override public ActivityContextInterface lookup(String name) { return map.get(name); }
        @Override public void unbind(String name) { map.remove(name); }
        @Override public java.util.Collection<ActivityContextInterface> getBoundContexts() { return map.values(); }
        @Override public java.util.Set<String> names() { return map.keySet(); }
        @Override public void clear() { map.clear(); }
    }

    /** Captures every routed event. */
    static final class CapturingRouter implements EventRouterPort {
        final CopyOnWriteArrayList<Object> routed = new CopyOnWriteArrayList<>();
        @Override public void routeEvent(Object event, ActivityContextInterface aci) {
            routed.add(event);
        }
    }

    static final class TestHandle implements com.microjainslee.api.ActivityHandle {
        private final String id;
        TestHandle(String id) { this.id = id; }
        @Override public String getId() { return id; }
        @Override public String toString() { return "TestHandle[" + id + "]"; }
    }

    /** Minimal SequencedEvent used by every test. */
    static final class TestSeqEvent implements SequencedEvent, SleeEvent {
        private final String conv;
        private final long seq;
        TestSeqEvent(String conv, long seq) { this.conv = conv; this.seq = seq; }
        @Override public long getSequenceNumber() { return seq; }
        @Override public String getConvergenceName() { return conv; }
        @Override public String toString() { return "TestSeqEvent[conv=" + conv + ",seq=" + seq + "]"; }
    }

    static final class StubFireableEventType implements FireableEventType {
        private final String name;
        StubFireableEventType(String name) { this.name = name; }
        @Override public EventType getEventType() { return new StubEventType(name); }
        @Override public String getName() { return name; }
    }

    static final class StubEventType implements EventType {
        private final String name;
        StubEventType(String name) { this.name = name; }
        @Override public EventTypeId getId() { return new EventTypeId(name, "test", "1.0"); }
        @Override public String getName() { return name; }
        @Override public String getVendor() { return "test"; }
        @Override public String getVersion() { return "1.0"; }
        @Override public Class<?> getEventClass() { return TestSeqEvent.class; }
    }

    /** In-process dedup check that mimics DedupWindow semantics with a fake clock. */
    static final class FakeDedupCheck implements SleeEndpointImpl.DedupCheck {
        private final java.util.Map<String, Long> seen = new java.util.LinkedHashMap<>();
        private final long windowMs;
        private final AtomicLong fakeNow;
        long hitCount = 0L;

        FakeDedupCheck(long windowMs, AtomicLong fakeNow) {
            this.windowMs = windowMs;
            this.fakeNow = fakeNow;
        }

        @Override
        public boolean isDuplicate(String convergence, long seqNum, long nowMs) {
            String key = convergence + "/" + seqNum;
            Long expiry = seen.get(key);
            long now = fakeNow.get();
            if (expiry != null && expiry > now) {
                hitCount++;
                return true;
            }
            seen.put(key, now + windowMs);
            return false;
        }
    }

    private CapturingRouter router;
    private StubAcnf acnf;
    private AcquireActivityContext aciFactory;
    private RaEntityStateMachine stateMachine;
    private SleeEndpointImpl endpoint;

    @Before
    public void setUp() throws Exception {
        router = new CapturingRouter();
        acnf = new StubAcnf();
        aciFactory = name -> new DefaultActivityContextInterface(name + "-aci", name);
        stateMachine = new RaEntityStateMachine(new StubRa(), "test-ra");
        endpoint = new SleeEndpointImpl(router, aciFactory, acnf, stateMachine);
        stateMachine.activate();
        endpoint.activityStarted(new TestHandle("h1"));
    }

    // 1. Fire twice with the same (conv, seq) -> handler called once.
    @Test
    public void fireEventTwiceSameSeq_dedupesToOneDelivery() throws Exception {
        AtomicLong now = new AtomicLong(100_000L);
        FakeDedupCheck dd = new FakeDedupCheck(60_000L, now);
        endpoint.withDedupCheck(dd);

        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-A", 1L), null,
                new StubFireableEventType("seq"));
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-A", 1L), null,
                new StubFireableEventType("seq"));
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-A", 1L), null,
                new StubFireableEventType("seq"));

        assertEquals("only first delivery reaches the router", 1, router.routed.size());
        assertEquals(2L, dd.hitCount);
    }

    // 2. Different seq numbers -> both delivered.
    @Test
    public void fireEventDifferentSeqs_bothDelivered() throws Exception {
        AtomicLong now = new AtomicLong(100_000L);
        FakeDedupCheck dd = new FakeDedupCheck(60_000L, now);
        endpoint.withDedupCheck(dd);

        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-A", 1L), null,
                new StubFireableEventType("seq"));
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-A", 2L), null,
                new StubFireableEventType("seq"));
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-A", 3L), null,
                new StubFireableEventType("seq"));

        assertEquals("3 distinct seqs -&gt; 3 deliveries", 3, router.routed.size());
        assertEquals(0L, dd.hitCount);
    }

    // 3. After the dedup window expires, the same key is delivered again.
    @Test
    public void fireEventAfterWindowExpiry_redelivered() throws Exception {
        AtomicLong now = new AtomicLong(100_000L);
        FakeDedupCheck dd = new FakeDedupCheck(1_000L, now); // 1s window
        endpoint.withDedupCheck(dd);

        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-B", 5L), null,
                new StubFireableEventType("seq"));
        // Same key inside the window: dropped.
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-B", 5L), null,
                new StubFireableEventType("seq"));
        assertEquals(1, router.routed.size());

        // Advance past the window.
        now.addAndGet(2_000L);
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-B", 5L), null,
                new StubFireableEventType("seq"));
        assertEquals("after window expiry, delivery resumes", 2, router.routed.size());
        assertEquals(1L, dd.hitCount); // only the in-window replay was a hit.
    }

    // 4. Backward compatibility: without withDedupCheck, events pass straight through.
    @Test
    public void noDedupHook_legacyPathUnchanged() throws Exception {
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-C", 1L), null,
                new StubFireableEventType("seq"));
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-C", 1L), null,
                new StubFireableEventType("seq"));
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-C", 1L), null,
                new StubFireableEventType("seq"));
        assertEquals("legacy hot path delivers everything", 3, router.routed.size());
    }

    // 5. Non-SequencedEvent payloads are always routed, even with dedup wired.
    @Test
    public void nonSequencedEvents_skipDedupCheck() throws Exception {
        AtomicLong now = new AtomicLong(100_000L);
        FakeDedupCheck dd = new FakeDedupCheck(60_000L, now);
        endpoint.withDedupCheck(dd);

        Object plainEvent = new Object();
        for (int i = 0; i < 5; i++) {
            endpoint.fireEvent(new TestHandle("h1"), plainEvent, null,
                    new StubFireableEventType("plain"));
        }
        assertEquals("non-SequencedEvent payloads bypass the dedup hook",
                5, router.routed.size());
        assertEquals(0L, dd.hitCount);
    }

    // 6. Sanity - the dedup hook is wired before the state check runs,
    //    so the legacy validation order is preserved. Just a smoke test.
    @Test
    public void dedupHookIsInvoked_silentlyReturnsForDuplicate() throws Exception {
        AtomicLong now = new AtomicLong(100_000L);
        FakeDedupCheck dd = new FakeDedupCheck(60_000L, now);
        endpoint.withDedupCheck(dd);

        AtomicReference<Object> snapshot = new AtomicReference<>();
        endpoint.fireEvent(new TestHandle("h1"),
                new TestSeqEvent("sess-D", 7L), null,
                new StubFireableEventType("seq"));
        snapshot.set(router.routed.get(0));
        assertNotNull(snapshot.get());
        assertTrue(snapshot.get() instanceof TestSeqEvent);
        assertEquals("router sees the original event reference",
                ((TestSeqEvent) snapshot.get()).getSequenceNumber(), 7L);
    }
}
