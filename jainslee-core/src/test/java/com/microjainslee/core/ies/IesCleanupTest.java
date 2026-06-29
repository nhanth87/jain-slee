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

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.InitialEventSelect;
import com.microjainslee.core.VirtualThreadSbbEntityPool;
import com.microjainslee.core.removal.EntityRemovalBus;
import com.microjainslee.core.removal.EntityRemovalEvent;
import com.microjainslee.core.removal.EntityRemovalEvent.RemovalReason;

import org.junit.Before;
import org.junit.Test;

import java.io.Serializable;
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
 * Sprint S9.3 — End-to-end IES convergence cleanup after entity removal.
 *
 * <p>Closes <b>GAP-SR-7</b>: previously the
 * {@link InitialEventSelectorDispatcher} never received a removal
 * notification when an SBB entity was recycled, leaving stale convergence
 * mappings in the dispatcher index. The fix is two-pronged:
 *
 * <ol>
 *   <li>The dispatcher exposes
 *       {@link InitialEventSelectorDispatcher#getConvergenceKeyFor(String)}
 *       — used by the kernel to capture the convergence key right before
 *       publishing a removal event.</li>
 *   <li>{@link VirtualThreadSbbEntityPool.IesCleanupAdapter} is wired as
 *       a subscriber on the {@link EntityRemovalBus} during
 *       {@code MicroSleeContainer.setInitialEventSelectorDispatcher(...)};
 *       every published event routes through
 *       {@link InitialEventSelectorDispatcher#removeConvergencesFor(String)}.</li>
 * </ol>
 *
 * <p>These tests exercise path 2 — the bus-driven cleanup — in isolation.
 * The {@link MinimalPool} SPI implementation intentionally has a
 * <i>no-op</i> {@link MinimalPool#onEntityRemoved(String, Consumer)} so
 * the only way the convergence index can shrink is via the bus. This
 * mirrors real-world wiring where the kernel entity pool does NOT push
 * per-entity removal callbacks into the dispatcher (it publishes on the
 * bus instead, to keep the pool agnostic of IES internals).</p>
 */
public class IesCleanupTest {

    /**
     * Minimal in-memory SPI pool. Mirrors the same shape as
     * {@code InitialEventSelectorDispatcher.SbbEntityPool}. The
     * {@code onEntityRemoved} hook is a NO-OP so the only path that can
     * shrink the convergence index is the {@link EntityRemovalBus} ->
     * {@link VirtualThreadSbbEntityPool.IesCleanupAdapter} chain.
     */
    static class MinimalPool implements InitialEventSelectorDispatcher.SbbEntityPool {
        final AtomicInteger seq = new AtomicInteger();
        final Set<String> alive = ConcurrentHashMap.newKeySet();

        @Override
        public String allocateNew(Class<?> sbbClass) {
            String id = "sbb-" + seq.incrementAndGet();
            alive.add(id);
            return id;
        }

        @Override
        public boolean contains(String entityId) {
            return alive.contains(entityId);
        }

        @Override
        public void onEntityRemoved(String entityId, Consumer<String> callback) {
            // INTENTIONALLY NO-OP: simulates a kernel pool that does not
            // drive per-entity removal callbacks into the dispatcher.
            // The cleanup path under test is the EntityRemovalBus path.
        }
    }

    /** Marker event used by the IES dispatcher test fixtures. */
    static final class CleanupTestEvent implements SleeEvent, Serializable {
        private static final long serialVersionUID = 1L;
        private final String tag;
        CleanupTestEvent(String tag) { this.tag = tag; }
        public String getTag() { return tag; }
    }

    /**
     * SBB whose IES method returns the convergence key {@code "session-1"}.
     * Used by Tests 1, 3. Has a no-arg constructor so the IES dispatcher
     * can instantiate it reflectively (see
     * {@link InitialEventSelectorDispatcher#invokeIes}).
     */
    static final class Session1Sbb implements Sbb {
        @InitialEventSelect
        public InitialEventSelectResult selectInitial(InitialEventSelectCondition c) {
            return InitialEventSelectResult.forSession("session-1", true);
        }
    }

    /**
     * SBB whose IES method returns the convergence key taken from the
     * event's {@code tag} field. Used by Test 2 to allocate two entities
     * under distinct convergence keys from a single SBB class. Has a
     * no-arg constructor for IES instantiation.
     */
    static final class PerEventKeySbb implements Sbb {
        @InitialEventSelect
        public InitialEventSelectResult selectInitial(InitialEventSelectCondition c) {
            CleanupTestEvent e = (CleanupTestEvent) c.getEvent();
            return InitialEventSelectResult.forSession(e.getTag(), true);
        }
    }

    /** Stateless SBB (no @InitialEventSelect) - IES must NOT index it. */
    static final class StatelessSbb implements Sbb { }

    // -----------------------------------------------------------------
    // Test fixtures
    // -----------------------------------------------------------------

    private MinimalPool pool;
    private InitialEventSelectorDispatcher dispatcher;
    private EntityRemovalBus bus;
    private VirtualThreadSbbEntityPool.IesCleanupAdapter cleanupAdapter;

    @Before
    public void setUp() {
        pool = new MinimalPool();
        dispatcher = new InitialEventSelectorDispatcher(pool);
        bus = new EntityRemovalBus();
        // Mirror the kernel wiring from
        // MicroSleeContainer.setInitialEventSelectorDispatcher(...):
        // an IesCleanupAdapter is registered as a subscriber on the bus.
        cleanupAdapter = new VirtualThreadSbbEntityPool.IesCleanupAdapter(dispatcher);
        bus.subscribe(cleanupAdapter);
    }

    // -----------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------

    /**
     * <b>Test 1:</b> IES convergence must be removed from the index
     * after the kernel publishes an {@link EntityRemovalEvent} for the
     * owning entity. Closes the original GAP-SR-7 repro.
     *
     * <ol>
     *   <li>Register an SBB whose IES method returns convergence
     *       {@code "session-1"}.</li>
     *   <li>Allocate an entity via {@code resolveTarget}.</li>
     *   <li>Assert the convergence index maps {@code "session-1"} to
     *       the allocated entity id.</li>
     *   <li>Publish a removal event on the bus.</li>
     *   <li>Assert the convergence index no longer contains
     *       {@code "session-1"}.</li>
     * </ol>
     */
    @Test
    public void iesConvergence_cleanedUp_afterEntityRemoval() {
        String entityId = dispatcher.resolveTarget(
                new CleanupTestEvent("begin"), null, Session1Sbb.class);
        assertNotNull("IES must allocate an entity for an initial event", entityId);

        // The dispatcher's getConvergenceKeyFor must return "session-1".
        assertEquals("session-1", dispatcher.getConvergenceKeyFor(entityId));
        assertEquals("convergence index must hold the new mapping",
                1, dispatcher.activeConvergenceCount());

        long now = System.currentTimeMillis();
        bus.publish(new EntityRemovalEvent(
                entityId, "session-1",
                RemovalReason.SBB_SELF_REMOVE, now));

        assertEquals("convergence must be cleaned up after entity removal",
                0, dispatcher.activeConvergenceCount());
        assertNull("reverse lookup must return null after cleanup",
                dispatcher.getConvergenceKeyFor(entityId));
        assertEquals("bus must record one publish", 1, bus.getPublishCount());
    }

    /**
     * <b>Test 2:</b> Removing one entity must NOT touch the convergence
     * entry of another. Two entities are allocated under distinct
     * convergence keys, one is removed, the other must survive intact.
     */
    @Test
    public void iesConvergence_preserved_forOtherEntities() {
        String entA = dispatcher.resolveTarget(
                new CleanupTestEvent("conv-A"), null, PerEventKeySbb.class);
        String entB = dispatcher.resolveTarget(
                new CleanupTestEvent("conv-B"), null, PerEventKeySbb.class);

        assertNotNull(entA);
        assertNotNull(entB);
        assertFalse("entities must be distinct", entA.equals(entB));
        assertEquals("two convergences registered",
                2, dispatcher.activeConvergenceCount());

        // Remove A only.
        bus.publish(new EntityRemovalEvent(
                entA, "conv-A", RemovalReason.SBB_SELF_REMOVE,
                System.currentTimeMillis()));

        assertEquals("only one convergence remains", 1,
                dispatcher.activeConvergenceCount());
        assertNull("A must no longer be mapped",
                dispatcher.getConvergenceKeyFor(entA));
        assertEquals("B must still be mapped to its convergence",
                "conv-B", dispatcher.getConvergenceKeyFor(entB));
        assertTrue("B must still be alive in the pool",
                pool.contains(entB));
    }

    /**
     * <b>Test 3:</b> A removal event for an unrelated entity must not
     * disturb the convergence index. The cleanup adapter only ever
     * deletes mappings that point at the published entityId.
     */
    @Test
    public void iesConvergence_notCleaned_onNonMatchingEvent() {
        String entLive = dispatcher.resolveTarget(
                new CleanupTestEvent("session-1"), null, Session1Sbb.class);
        assertNotNull(entLive);
        assertEquals(1, dispatcher.activeConvergenceCount());

        // Cross-check: getConvergenceKeyFor(entLive) returns "session-1".
        assertEquals("session-1",
                dispatcher.getConvergenceKeyFor(entLive));

        // Event for a DIFFERENT entity (ghost-1). The adapter must not
        // remove the "session-1" -> entLive mapping.
        bus.publish(new EntityRemovalEvent(
                "ghost-1", null,
                RemovalReason.OPERATOR, System.currentTimeMillis()));

        assertEquals("matching convergence must remain",
                1, dispatcher.activeConvergenceCount());
        // The reverse lookup must still return the convergence name "session-1".
        assertEquals("reverse lookup must still resolve the convergence",
                "session-1", dispatcher.getConvergenceKeyFor(entLive));
    }

    /**
     * <b>Test 4:</b> Publishing a removal event for an entity that was
     * never IES-indexed (e.g. a stateless SBB allocation) must NOT
     * throw, and must NOT change the convergence index. This is the
     * graceful-handling guarantee requested in S9.3.
     */
    @Test
    public void noIESConfig_gracefulHandling() {
        // Allocate a stateless entity. The dispatcher does NOT index it
        // (no @InitialEventSelect method) so the convergence index
        // stays empty.
        String statelessId = dispatcher.resolveTarget(
                new CleanupTestEvent("ignored"), null, StatelessSbb.class);
        assertNotNull("stateless dispatch must still allocate", statelessId);
        assertEquals("convergence index must be empty for stateless SBBs",
                0, dispatcher.activeConvergenceCount());

        // Now publish a removal event for that stateless entity id.
        // The adapter's accept() must NOT throw and must NOT touch the
        // (already-empty) index.
        try {
            bus.publish(new EntityRemovalEvent(
                    statelessId, null,
                    RemovalReason.SBB_SELF_REMOVE,
                    System.currentTimeMillis()));
        } catch (Exception ex) {
            org.junit.Assert.fail("publish on a stateless entity id must "
                    + "not throw; got: " + ex);
        }

        assertEquals("index must remain empty",
                0, dispatcher.activeConvergenceCount());
        assertEquals("bus still counted the publish",
                1, bus.getPublishCount());
    }
}
