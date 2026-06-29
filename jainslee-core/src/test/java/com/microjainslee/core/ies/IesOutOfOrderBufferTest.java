/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.ies;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SequencedEvent;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.InitialEventSelect;
import com.microjainslee.core.EventDeliveryMode;
import com.microjainslee.core.EventRouter;
import com.microjainslee.core.ordering.OutOfOrderBuffer;
import com.microjainslee.ra.DefaultActivityContextInterface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Sprint S8.3 + S8.4 — wiring tests for {@link OutOfOrderBuffer}
 * inside {@link InitialEventSelectorDispatcher}. Verifies the five
 * contract paths required by the design.
 *
 * @author Tran Nhan (nhanth87)
 */
public class IesOutOfOrderBufferTest {

    /** SBB pool stub. */
    static class FakePool implements InitialEventSelectorDispatcher.SbbEntityPool {
        final AtomicInteger seq = new AtomicInteger();
        final Set<String> alive = ConcurrentHashMap.newKeySet();
        final Map<String, Consumer<String>> callbacks = new ConcurrentHashMap<>();

        @Override
        public String allocateNew(Class<?> sbbClass) {
            String id = "ent-" + seq.incrementAndGet();
            alive.add(id);
            return id;
        }

        @Override
        public boolean contains(String entityId) {
            return alive.contains(entityId);
        }

        @Override
        public void onEntityRemoved(String entityId, Consumer<String> callback) {
            callbacks.put(entityId, callback);
        }
    }

    /** Event that implements BOTH {@link SleeEvent} AND {@link SequencedEvent}. */
    static final class SequencedSleeEvent implements SleeEvent, SequencedEvent {
        private final String convergence;
        private final long seq;
        private final String tag;
        SequencedSleeEvent(String convergence, long seq, String tag) {
            this.convergence = convergence;
            this.seq = seq;
            this.tag = tag;
        }
        @Override public long getSequenceNumber() { return seq; }
        @Override public String getConvergenceName() { return convergence; }
        @Override public String toString() { return "SequencedSleeEvent{" + tag + ",seq=" + seq + "}"; }
    }

    /** Event that implements ONLY {@link SleeEvent} (NOT {@link SequencedEvent}). */
    static final class PlainSleeEvent implements SleeEvent {
        private final String tag;
        PlainSleeEvent(String tag) { this.tag = tag; }
        @Override public String toString() { return "PlainSleeEvent{" + tag + "}"; }
    }

    /** Session-aware SBB: "begin" initial=true, "cont*" initial=false. */
    static class SequencedSessionSbb implements Sbb {
        @InitialEventSelect
        public InitialEventSelectResult selectInitial(InitialEventSelectCondition c) {
            Object e = c.getEvent();
            String key;
            if (e instanceof SequencedSleeEvent) {
                key = "begin".equals(((SequencedSleeEvent) e).tag) ? "begin" : "cont";
            } else {
                key = "begin".equals(((PlainSleeEvent) e).tag) ? "begin" : "cont";
            }
            String convergence = (e instanceof SequencedSleeEvent)
                    ? ((SequencedSleeEvent) e).getConvergenceName()
                    : "plain-conv";
            return InitialEventSelectResult.forSession(convergence, "begin".equals(key));
        }
    }

    /** Minimal EventRouter stand-in — captures routeEvent() invocations. */
    static class CapturingRouter extends EventRouter {
        final CopyOnWriteArrayList<Object> routed = new CopyOnWriteArrayList<>();
        CapturingRouter() {
            super(8, false, false, EventDeliveryMode.SYNC);
        }
        @Override
        public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
            routed.add(event);
        }
        @Override
        public void shutdown() { /* no-op for test */ }
    }

    private FakePool pool;
    private InitialEventSelectorDispatcher dispatcher;
    private DefaultActivityContextInterface aci;
    private OutOfOrderBuffer oob;

    @Before
    public void setUp() {
        pool = new FakePool();
        dispatcher = new InitialEventSelectorDispatcher(pool);
        aci = new DefaultActivityContextInterface("test-aci", "h-1");
        oob = new OutOfOrderBuffer(2, 60_000L, 1_000L);
    }

    @After
    public void tearDown() {
        if (oob != null) oob.shutdown();
    }

    /** Test 1 — non-initial SequencedEvent without matching entity is buffered, not dropped. */
    @Test
    public void nonInitialSequencedEvent_withoutEntity_isBufferedNotDropped() {
        dispatcher.setOutOfOrderBuffer(oob);
        SequencedSleeEvent cont = new SequencedSleeEvent("conv-1", 5L, "cont-1");
        String result = dispatcher.resolveTarget(cont, aci, SequencedSessionSbb.class);
        assertNull("non-initial event with no entity must return null (OOB-buffered)", result);
        assertEquals("OOB must contain the buffered event", 1, oob.depthFor("conv-1"));
        assertEquals("No entity should have been allocated yet", 0, dispatcher.activeConvergenceCount());
    }

    /** Test 2 — initial event arrives later; OOB drained FIFO before returning newId. */
    @Test
    public void initialEventLater_drainsOobFifoBeforeReturningNewId() {
        OutOfOrderBuffer bigOob = new OutOfOrderBuffer(16, 60_000L, 1_000L);
        try {
            CapturingRouter router = new CapturingRouter();
            dispatcher.setOutOfOrderBuffer(bigOob);
            dispatcher.setEventRouterPort(router);

            SequencedSleeEvent c1 = new SequencedSleeEvent("conv-2", 2L, "cont-1");
            SequencedSleeEvent c2 = new SequencedSleeEvent("conv-2", 3L, "cont-2");
            SequencedSleeEvent c3 = new SequencedSleeEvent("conv-2", 4L, "cont-3");
            dispatcher.resolveTarget(c1, aci, SequencedSessionSbb.class);
            dispatcher.resolveTarget(c2, aci, SequencedSessionSbb.class);
            dispatcher.resolveTarget(c3, aci, SequencedSessionSbb.class);
            assertEquals(3, bigOob.depthFor("conv-2"));
            assertEquals("No routing yet — only buffered", 0, router.routed.size());

            SequencedSleeEvent begin = new SequencedSleeEvent("conv-2", 1L, "begin");
            String entityId = dispatcher.resolveTarget(begin, aci, SequencedSessionSbb.class);
            assertNotNull("initial event must allocate a fresh entity", entityId);
            assertEquals("all three buffered events must have been re-routed in FIFO order",
                    3, router.routed.size());
            assertEquals(c1, router.routed.get(0));
            assertEquals(c2, router.routed.get(1));
            assertEquals(c3, router.routed.get(2));
            assertEquals("OOB for conv-2 must be empty after drain", 0, bigOob.depthFor("conv-2"));
            assertEquals(1, dispatcher.activeConvergenceCount());
        } finally {
            bigOob.shutdown();
        }
    }

    /** Test 3 — non-initial SequencedEvent WITHOUT OOB wired is dropped (backward compat). */
    @Test
    public void nonInitialSequencedEvent_withoutOobWired_isDroppedSilently() {
        SequencedSleeEvent cont = new SequencedSleeEvent("conv-3", 5L, "cont-1");
        String result = dispatcher.resolveTarget(cont, aci, SequencedSessionSbb.class);
        assertNull("non-initial event without OOB must be dropped (spec 7.5.5)", result);
        assertEquals(0, dispatcher.activeConvergenceCount());
        assertEquals("no external buffer must be touched", 0, oob.depthFor("conv-3"));
    }

    /** Test 4 — non-initial NON-SequencedEvent dropped even with OOB wired. */
    @Test
    public void nonInitialNonSequencedEvent_evenWithOobWired_isDropped() {
        dispatcher.setOutOfOrderBuffer(oob);
        PlainSleeEvent plain = new PlainSleeEvent("cont-plain");
        String result = dispatcher.resolveTarget(plain, aci, SequencedSessionSbb.class);
        assertNull("non-SequencedEvent must NOT enter the OOB", result);
        assertEquals("OOB must remain empty", 0, oob.depthFor("plain-conv"));
        assertEquals(0, oob.bufferedConvergenceCount());
    }

    /** Test 5 — OOB capacity overflow: oldest dropped, monotonic counter increases. */
    @Test
    public void oobCapacityOverflow_dropsOldestWithMonotonicCounter() {
        dispatcher.setOutOfOrderBuffer(oob);
        long dropsBefore = oob.getOverflowDrops();
        List<SequencedSleeEvent> pushed = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            SequencedSleeEvent e = new SequencedSleeEvent("conv-overflow", i, "ev-" + i);
            pushed.add(e);
            String r = dispatcher.resolveTarget(e, aci, SequencedSessionSbb.class);
            assertNull("non-initial event must return null (OOB-buffered or dropped)", r);
        }
        assertEquals("buffer must be at capacity", 2, oob.depthFor("conv-overflow"));
        long dropsAfter = oob.getOverflowDrops();
        assertEquals("exactly two overflows must have been recorded", dropsBefore + 2, dropsAfter);
        List<OutOfOrderBuffer.BufferedEvent> survivors = oob.drainAll("conv-overflow");
        assertEquals(2, survivors.size());
        assertEquals(pushed.get(2), survivors.get(0).event());
        assertEquals(pushed.get(3), survivors.get(1).event());
    }
}
