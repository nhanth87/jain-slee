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
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
 * Unit tests for {@link InitialEventSelectorDispatcher} — verifies the four
 * contract paths required by Perfect Core S3:
 *
 * <ol>
 *   <li>Stateless event (no @InitialEventSelect) → always allocate new entity.</li>
 *   <li>Session event with matching convergence       → return existing entity.</li>
 *   <li>Session event without match + initial=false  → silently dropped.</li>
 *   <li>Session event without match + initial=true   → allocate new entity.</li>
 * </ol>
 */
public class InitialEventSelectorDispatcherTest {

    /** Simple in-memory pool used by every test in this class. */
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

        void remove(String entityId) {
            alive.remove(entityId);
            Consumer<String> cb = callbacks.remove(entityId);
            if (cb != null) cb.accept(entityId);
        }
    }

    /** Test event — just a marker. */
    static class TestEvent { final String key; TestEvent(String key) { this.key = key; } }

    /** Stateless SBB (no @InitialEventSelect method). */
    static class StatelessSbb implements Sbb { }

    /** Session SBB with @InitialEventSelect. */
    static class SessionSbb implements Sbb {
        @InitialEventSelect
        public InitialEventSelectResult selectInitial(InitialEventSelectCondition c) {
            TestEvent e = (TestEvent) c.getEvent();
            // First message (key "begin") is initial; subsequent ("cont") are not.
            boolean isBegin = "begin".equals(e.key);
            return InitialEventSelectResult.forSession("session-1", isBegin);
        }
    }

    /** SBB that always returns the stateless shortcut. */
    static class StatelessIeSbb implements Sbb {
        @InitialEventSelect
        public InitialEventSelectResult selectInitial(InitialEventSelectCondition c) {
            return InitialEventSelectResult.stateless();
        }
    }

    /** SBB whose IES method throws — dispatcher must catch and return null. */
    static class ThrowingIesSbb implements Sbb {
        @InitialEventSelect
        public InitialEventSelectResult selectInitial(InitialEventSelectCondition c) {
            throw new RuntimeException("boom");
        }
    }

    private FakePool pool;
    private InitialEventSelectorDispatcher dispatcher;

    @Before
    public void setUp() {
        pool = new FakePool();
        dispatcher = new InitialEventSelectorDispatcher(pool);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stateless SBBs (no @InitialEventSelect declared)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void stateless_noIesMethod_alwaysAllocatesNew() {
        String id1 = dispatcher.resolveTarget(new TestEvent("a"), null, StatelessSbb.class);
        String id2 = dispatcher.resolveTarget(new TestEvent("a"), null, StatelessSbb.class);
        assertNotNull(id1);
        assertNotNull(id2);
        assertFalse("stateless SBB must allocate new entity every call", id1.equals(id2));
    }

    // ─────────────────────────────────────────────────────────────────────
    // SBBs WITH @InitialEventSelect
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void session_beginAllocatesNewAndIndexes() {
        String id = dispatcher.resolveTarget(new TestEvent("begin"), null, SessionSbb.class);
        assertNotNull(id);
        assertEquals(1, dispatcher.activeConvergenceCount());
        assertTrue("pool should mark entity alive", pool.contains(id));
    }

    @Test
    public void session_matchingConvergence_reusesExistingEntity() {
        String first = dispatcher.resolveTarget(new TestEvent("begin"), null, SessionSbb.class);
        // Second event for the SAME convergence key → IES says initial=false,
        // convergence="session-1" → dispatcher returns the first entity.
        String second = dispatcher.resolveTarget(new TestEvent("cont"), null, SessionSbb.class);
        assertEquals("continue message must hit the same entity", first, second);
    }

    @Test
    public void session_unmatchedNonInitial_isDropped() {
        // First "cont" without a prior "begin" → IES says initial=false,
        // no entity registered → dispatcher must return null (spec §7.5.5).
        String dropped = dispatcher.resolveTarget(new TestEvent("cont"), null, SessionSbb.class);
        assertNull("non-initial event without matching entity must be dropped", dropped);
        assertEquals(0, dispatcher.activeConvergenceCount());
    }

    @Test
    public void session_unmatchedInitial_allocatesNewEntity() {
        // First "begin" → no existing entity, initial=true → allocate new.
        String first = dispatcher.resolveTarget(new TestEvent("begin"), null, SessionSbb.class);
        assertNotNull(first);
        assertEquals(1, dispatcher.activeConvergenceCount());
        // Remove the entity from the pool BEFORE the second "begin" arrives →
        // the previous convergence entry is stale so the dispatcher must
        // allocate a new one.
        pool.remove(first);
        assertEquals("stale convergence entry must be cleaned", 0,
                dispatcher.activeConvergenceCount());

        String second = dispatcher.resolveTarget(new TestEvent("begin"), null, SessionSbb.class);
        assertNotNull(second);
        assertFalse("dispatcher must allocate fresh entity when previous was removed",
                first.equals(second));
    }

    @Test
    public void statelessShortcut_overwritesEveryTime() {
        StatelessIeSbb sbbCls = new StatelessIeSbb(); // not used as instance, just for class ref
        String a = dispatcher.resolveTarget(new TestEvent("x"), null, StatelessIeSbb.class);
        String b = dispatcher.resolveTarget(new TestEvent("x"), null, StatelessIeSbb.class);
        assertNotNull(a);
        assertNotNull(b);
        assertFalse("stateless shortcut must allocate a new entity every time", a.equals(b));
        assertEquals(0, dispatcher.activeConvergenceCount());
    }

    @Test
    public void throwingIesMethod_treatedAsNull_dropsEvent() {
        String r = dispatcher.resolveTarget(new TestEvent("any"), null, ThrowingIesSbb.class);
        assertNull("throwing IES must be treated as null → event dropped", r);
    }

    @Test
    public void nullSbbClass_returnsNull() {
        assertNull(dispatcher.resolveTarget(new TestEvent("x"), null, null));
    }

    @Test
    public void entityRemoval_convergenceIsAutoCleaned() {
        String id = dispatcher.resolveTarget(new TestEvent("begin"), null, SessionSbb.class);
        assertEquals(1, dispatcher.activeConvergenceCount());
        pool.remove(id);
        assertEquals("convergence entry must be removed via onEntityRemoved callback",
                0, dispatcher.activeConvergenceCount());
    }

    @Test
    public void removeConvergencesFor_dropsAllMappingsForEntity() {
        String id = dispatcher.resolveTarget(new TestEvent("begin"), null, SessionSbb.class);
        assertEquals(1, dispatcher.activeConvergenceCount());
        dispatcher.removeConvergencesFor(id);
        assertEquals(0, dispatcher.activeConvergenceCount());
    }

    @Test
    public void nullPoolInCtor_throws() {
        try {
            new InitialEventSelectorDispatcher(null);
            assertFalse("expected IllegalArgumentException", true);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
