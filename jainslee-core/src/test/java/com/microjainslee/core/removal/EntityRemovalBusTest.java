/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.removal;

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.InitialEventSelect;
import com.microjainslee.core.SimpleSbbLocalObject;
import com.microjainslee.core.VirtualThreadSbbEntityPool;
import com.microjainslee.core.ies.InitialEventSelectCondition;
import com.microjainslee.core.ies.InitialEventSelectResult;
import com.microjainslee.core.ies.InitialEventSelectorDispatcher;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Sprint S6 — EntityRemovalBus fan-out, defensive failure isolation,
 * unsubscribe + metric, multi-subscriber, null convergence key, exception
 * isolation, counter increment, plus a smoke test for
 * {@link VirtualThreadSbbEntityPool.IesCleanupAdapter} wiring and the
 * {@link SimpleSbbLocalObject.RemovalCause} defaulting.
 */
public class EntityRemovalBusTest {

    private static EntityRemovalEvent event(String entityId, String convergence) {
        return new EntityRemovalEvent(entityId, convergence,
                EntityRemovalEvent.RemovalReason.SBB_SELF_REMOVE, 1_700_000_000L);
    }

    /** Tiny in-memory pool so the IES dispatcher test does not depend on the kernel. */
    static class FakePool implements InitialEventSelectorDispatcher.SbbEntityPool {
        final AtomicInteger seq = new AtomicInteger();
        final Set<String> alive = ConcurrentHashMap.newKeySet();

        @Override public String allocateNew(Class<?> sbbClass) {
            String id = "ent-" + seq.incrementAndGet();
            alive.add(id);
            return id;
        }
        @Override public boolean contains(String entityId) { return alive.contains(entityId); }
        @Override public void onEntityRemoved(String entityId, Consumer<String> callback) { }
    }

    /** Test event used by IES dispatch tests. */
    static final class TestEvent implements SleeEvent {
        private static final long serialVersionUID = 1L;
        private final String name;
        TestEvent(String name) { this.name = name; }
        public String getName() { return name; }
    }

    /** Tiny SBB with one IES method returning a fixed convergence key. */
    public static final class TestIesSbb implements Sbb {
        @InitialEventSelect
        public InitialEventSelectResult ies(InitialEventSelectCondition c) {
            return InitialEventSelectResult.forSession("conv-x", true);
        }
    }

    /** 1. Fan-out: every subscriber receives the event. */
    @Test
    public void publish_notifiesAllSubscribers() {
        EntityRemovalBus bus = new EntityRemovalBus();
        List<String> received1 = new ArrayList<>();
        List<String> received2 = new ArrayList<>();
        bus.subscribe(e -> received1.add(e.entityId()));
        bus.subscribe(e -> received2.add(e.entityId()));
        bus.publish(event("sbb-1", "conv-1"));
        assertEquals(1, received1.size());
        assertEquals(1, received2.size());
        assertEquals("sbb-1", received1.get(0));
        assertEquals("sbb-1", received2.get(0));
    }

    /** 2. Defensive fan-out — one subscriber throwing must NOT abort the rest. */
    @Test
    public void publish_oneSubscriberThrows_othersStillReceive() {
        EntityRemovalBus bus = new EntityRemovalBus();
        AtomicInteger counter = new AtomicInteger();
        bus.subscribe(e -> { throw new RuntimeException("boom"); });
        bus.subscribe(e -> counter.incrementAndGet());
        bus.subscribe(e -> { throw new IllegalStateException("still boom"); });
        bus.subscribe(e -> counter.incrementAndGet());
        bus.publish(event("sbb-x", null));
        assertEquals("both non-throwing subscribers must still fire", 2, counter.get());
    }

    /** 3. Unsubscribe removes the listener — no further callbacks. */
    @Test
    public void unsubscribe_removesListener() {
        EntityRemovalBus bus = new EntityRemovalBus();
        AtomicInteger counter = new AtomicInteger();
        Consumer<EntityRemovalEvent> listener = e -> counter.incrementAndGet();
        bus.subscribe(listener);
        bus.publish(event("sbb-a", null));
        bus.unsubscribe(listener);
        bus.publish(event("sbb-b", null));
        assertEquals(1, counter.get());
        assertEquals("subscriber count must reflect the unsubscribe", 0, bus.subscriberCount());
    }

    /** 4. Metric — publish count increments monotonically; null publish is a no-op. */
    @Test
    public void getPublishCount_incrementsOnEveryPublish() {
        EntityRemovalBus bus = new EntityRemovalBus();
        assertEquals(0, bus.getPublishCount());
        bus.publish(event("a", null));
        bus.publish(event("b", null));
        bus.publish(event("c", null));
        assertEquals(3, bus.getPublishCount());
        bus.publish(null);
        assertEquals(3, bus.getPublishCount());
    }

    /** 5. Multi-subscriber with different reactions all run. */
    @Test
    public void multipleSubscribers_eachReceiveIndependently() {
        EntityRemovalBus bus = new EntityRemovalBus();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        AtomicInteger c = new AtomicInteger();
        bus.subscribe(e -> a.incrementAndGet());
        bus.subscribe(e -> b.incrementAndGet());
        bus.subscribe(e -> c.incrementAndGet());
        for (int i = 0; i < 5; i++) {
            bus.publish(event("sbb-" + i, "conv-" + i));
        }
        assertEquals(5, a.get());
        assertEquals(5, b.get());
        assertEquals(5, c.get());
    }

    /** 6. Null convergence key is allowed — subscribers see null, not NPE. */
    @Test
    public void nullConvergenceKey_doesNotThrow() {
        EntityRemovalBus bus = new EntityRemovalBus();
        StringBuilder captured = new StringBuilder();
        bus.subscribe(e -> captured.append(String.valueOf(e.convergenceKey())));
        bus.publish(event("sbb-1", null));
        assertEquals("null", captured.toString());
    }

    /** 7. Subscriber exception is isolated — surrounding state is preserved. */
    @Test
    public void subscriberException_doesNotCorruptBusState() {
        EntityRemovalBus bus = new EntityRemovalBus();
        AtomicInteger sideEffect = new AtomicInteger();
        bus.subscribe(e -> { throw new RuntimeException("first"); });
        bus.subscribe(e -> sideEffect.addAndGet(2));
        bus.subscribe(e -> { throw new RuntimeException("second"); });
        bus.subscribe(e -> sideEffect.addAndGet(3));
        bus.publish(event("sbb-x", "conv-x"));
        assertEquals("surviving subscribers still ran", 5, sideEffect.get());
        bus.publish(event("sbb-y", "conv-y"));
        assertEquals(10, sideEffect.get());
    }

    /** 8. Counter increments across many sequential publishes. */
    @Test
    public void counter_advancesAcrossManyPublishes() {
        EntityRemovalBus bus = new EntityRemovalBus();
        for (int i = 0; i < 1000; i++) {
            bus.publish(event("sbb-" + i, "conv-" + i));
        }
        assertEquals(1000, bus.getPublishCount());
    }

    /** 9. Contract — null subscriber rejected; null event ignored. */
    @Test
    public void nullSubscriberRejected_nullEventIgnored() {
        EntityRemovalBus bus = new EntityRemovalBus();
        try {
            bus.subscribe(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        bus.publish(null);
        assertEquals(0, bus.getPublishCount());
    }

    /** 10. Sanity — the event record carries entityId + reason + timestamp. */
    @Test
    public void eventRecord_carriesAllFields() {
        EntityRemovalEvent e = new EntityRemovalEvent(
                "entity-1", "conv-1",
                EntityRemovalEvent.RemovalReason.TIMER_EXPIRED, 42L);
        assertEquals("entity-1", e.entityId());
        assertEquals("conv-1", e.convergenceKey());
        assertEquals(EntityRemovalEvent.RemovalReason.TIMER_EXPIRED, e.reason());
        assertEquals(42L, e.timestampMs());
        assertNotNull(e.toString());
    }

    /** 11. Sanity — all RemovalReason enum values are reachable. */
    @Test
    public void removalReason_allValuesReachable() {
        assertEquals(6, EntityRemovalEvent.RemovalReason.values().length);
        assertNotNull(EntityRemovalEvent.RemovalReason.valueOf("TIMER_EXPIRED"));
        assertNotNull(EntityRemovalEvent.RemovalReason.valueOf("SBB_SELF_REMOVE"));
        assertNotNull(EntityRemovalEvent.RemovalReason.valueOf("CASCADE_CHILD"));
        assertNotNull(EntityRemovalEvent.RemovalReason.valueOf("OPERATOR"));
        assertNotNull(EntityRemovalEvent.RemovalReason.valueOf("EXCEPTION_ROLLBACK"));
        assertNotNull(EntityRemovalEvent.RemovalReason.valueOf("HOT_REDEPLOY"));
    }

    /** 12. Sanity — null cause on SimpleSbbLocalObject defaults to SELF. */
    @Test
    public void simpleSbbLocalObject_removalCause_defaultsToSelf() {
        SimpleSbbLocalObject obj = new SimpleSbbLocalObject(
                new SbbID("sbb-test"),
                new Sbb() { });
        assertEquals(SimpleSbbLocalObject.RemovalCause.SELF, obj.getRemovalCause());
        obj.setRemovalCause(SimpleSbbLocalObject.RemovalCause.TIMER);
        assertEquals(SimpleSbbLocalObject.RemovalCause.TIMER, obj.getRemovalCause());
        obj.setRemovalCause(null);
        assertEquals(SimpleSbbLocalObject.RemovalCause.TIMER, obj.getRemovalCause());
    }

    /** 13. IesCleanupAdapter routes entityId through removeConvergencesFor(). */
    @Test
    public void iesCleanupAdapter_removesConvergencesForEntity() {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(0, 4, false);
        try {
            InitialEventSelectorDispatcher d =
                    new InitialEventSelectorDispatcher(new FakePool());
            EntityRemovalBus bus = new EntityRemovalBus();
            String id = d.resolveTarget(new TestEvent("begin"), null, TestIesSbb.class);
            assertEquals(1, d.activeConvergenceCount());
            bus.subscribe(new VirtualThreadSbbEntityPool.IesCleanupAdapter(d));
            bus.publish(event(id, "conv-x"));
            assertEquals("IesCleanupAdapter must remove the convergence", 0,
                    d.activeConvergenceCount());
        } finally {
            pool.shutdown();
        }
    }

    /** 14. Sanity — subscribers fire in publish() order. */
    @Test
    public void publishOrderIsSequential() {
        EntityRemovalBus bus = new EntityRemovalBus();
        List<Integer> order = new ArrayList<>();
        bus.subscribe(e -> order.add(1));
        bus.subscribe(e -> order.add(2));
        bus.subscribe(e -> order.add(3));
        bus.publish(event("sbb-1", null));
        assertEquals(Arrays.asList(1, 2, 3), order);
    }

    /** 15. Sanity — fresh bus reports zero subscribers + zero publishes. */
    @Test
    public void bus_initialState_isZeroSubscribersAndZeroPublished() {
        EntityRemovalBus bus = new EntityRemovalBus();
        assertEquals(0, bus.subscriberCount());
        assertEquals(0, bus.getPublishCount());
    }

    /** 16. Unsubscribe is idempotent. */
    @Test
    public void unsubscribe_isIdempotent() {
        EntityRemovalBus bus = new EntityRemovalBus();
        Consumer<EntityRemovalEvent> listener = e -> { };
        bus.subscribe(listener);
        bus.unsubscribe(listener);
        bus.unsubscribe(listener);
        assertEquals(0, bus.subscriberCount());
        assertTrue(bus.getPublishCount() == 0);
    }
}
