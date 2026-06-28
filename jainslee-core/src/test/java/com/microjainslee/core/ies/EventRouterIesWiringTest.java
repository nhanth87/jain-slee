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
import com.microjainslee.api.annotations.InitialEventSelect;
import com.microjainslee.core.EventRouter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end wiring tests for Perfect Core S3 — verifies that
 * {@link EventRouter#bindInitialEventSelectorDispatcher(Object)} and
 * {@link EventRouter#routeIncomingEvent(Object, ActivityContextInterface, Class)}
 * actually invoke the {@link InitialEventSelectorDispatcher} and surface its
 * result correctly.
 */
public class EventRouterIesWiringTest {

    static class TestEvent { final String key; TestEvent(String k) { this.key = k; } }

    static class FakePool implements InitialEventSelectorDispatcher.SbbEntityPool {
        final AtomicInteger seq = new AtomicInteger();
        final Set<String> alive = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<String, Consumer<String>> callbacks = new ConcurrentHashMap<>();

        @Override
        public String allocateNew(Class<?> sbbClass) {
            String id = "ent-" + seq.incrementAndGet();
            alive.add(id);
            return id;
        }
        @Override
        public boolean contains(String entityId) { return alive.contains(entityId); }
        @Override
        public void onEntityRemoved(String entityId, Consumer<String> callback) {
            callbacks.put(entityId, callback);
        }
    }

    static class SessionSbb implements Sbb {
        @InitialEventSelect
        public InitialEventSelectResult selectInitial(InitialEventSelectCondition c) {
            TestEvent e = (TestEvent) c.getEvent();
            boolean begin = "begin".equals(e.key);
            return InitialEventSelectResult.forSession("s1", begin);
        }
    }

    static class StatelessSbb implements Sbb { }

    private EventRouter router;

    @Before
    public void setUp() {
        router = new EventRouter(16);
    }

    @After
    public void tearDown() {
        if (router != null) router.shutdown();
    }

    @Test
    public void bindDispatcher_acceptsCorrectClass() {
        FakePool pool = new FakePool();
        InitialEventSelectorDispatcher d = new InitialEventSelectorDispatcher(pool);
        router.bindInitialEventSelectorDispatcher(d);
        assertEquals(d, router.getInitialEventSelectorDispatcher());
    }

    @Test(expected = IllegalArgumentException.class)
    public void bindDispatcher_rejectsForeignClass() {
        router.bindInitialEventSelectorDispatcher("not a dispatcher");
    }

    @Test
    public void bindDispatcher_nullClearsBinding() {
        FakePool pool = new FakePool();
        router.bindInitialEventSelectorDispatcher(new InitialEventSelectorDispatcher(pool));
        assertNotNull(router.getInitialEventSelectorDispatcher());
        router.bindInitialEventSelectorDispatcher(null);
        assertNull(router.getInitialEventSelectorDispatcher());
    }

    @Test
    public void routeIncomingEvent_withoutDispatcher_returnsNull() {
        // No dispatcher bound → router signals "fall back to legacy routing".
        String r = router.routeIncomingEvent(new TestEvent("x"), null, StatelessSbb.class);
        assertNull("no dispatcher → router must return null (legacy path)", r);
    }

    @Test
    public void routeIncomingEvent_sessionBegin_returnsEntityId() {
        FakePool pool = new FakePool();
        router.bindInitialEventSelectorDispatcher(new InitialEventSelectorDispatcher(pool));

        String id = router.routeIncomingEvent(new TestEvent("begin"), null, SessionSbb.class);
        assertNotNull(id);
        assertTrue("entity is alive in the pool", pool.contains(id));
    }

    @Test
    public void routeIncomingEvent_sessionContinue_returnsExistingEntity() {
        FakePool pool = new FakePool();
        router.bindInitialEventSelectorDispatcher(new InitialEventSelectorDispatcher(pool));

        String begin = router.routeIncomingEvent(new TestEvent("begin"), null, SessionSbb.class);
        String cont = router.routeIncomingEvent(new TestEvent("cont"), null, SessionSbb.class);
        assertEquals("continue event must reuse the begin entity", begin, cont);
    }

    @Test
    public void routeIncomingEvent_nonInitialUnmatched_returnsNull() {
        FakePool pool = new FakePool();
        router.bindInitialEventSelectorDispatcher(new InitialEventSelectorDispatcher(pool));

        // First message is a "cont" — no begin was ever received → drop.
        String r = router.routeIncomingEvent(new TestEvent("cont"), null, SessionSbb.class);
        assertNull("unmatched non-initial event must be dropped per spec §7.5.5", r);
    }

    @Test
    public void routeIncomingEvent_statelessSbb_alwaysAllocatesNew() {
        FakePool pool = new FakePool();
        router.bindInitialEventSelectorDispatcher(new InitialEventSelectorDispatcher(pool));

        String a = router.routeIncomingEvent(new TestEvent("x"), null, StatelessSbb.class);
        String b = router.routeIncomingEvent(new TestEvent("x"), null, StatelessSbb.class);
        assertNotNull(a);
        assertNotNull(b);
        assertFalse("stateless SBB → fresh entity every time", a.equals(b));
    }
}
