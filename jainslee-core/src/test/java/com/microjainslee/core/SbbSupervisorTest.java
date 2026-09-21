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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.AlarmLevel;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.core.supervision.SbbSupervisor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Production P3 — local SBB supervision integration tests.
 *
 * <p>Covers: supervisor bind/unbind lifecycle, the config gate, the
 * delivery-timeout force-restart (fresh instance + CMP + ACI re-attach),
 * the crash-loop restart ladder ending in DEAD + CRITICAL alarm, and the
 * refusal to restart legacy (non-pooled) SBBs.
 *
 * @author Tran Nhan (nhanth87)
 */
public class SbbSupervisorTest {

    private static final long DEADLINE_MS = 5_000L;

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        container = newContainer(true, 2, 0L, 1L);
        container.start();
    }

    @After
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    private static MicroSleeContainer newContainer(boolean supervision, int maxAttempts,
            long backoffBaseMs, long backoffMaxMs) {
        return new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
                        .sbbSupervisionEnabled(supervision)
                        .sbbRestartMaxAttempts(maxAttempts)
                        .sbbRestartBackoffBaseMs(backoffBaseMs)
                        .sbbRestartBackoffMaxMs(backoffMaxMs)
                        .build());
    }

    // ───────────────────────────────────────────────────────────────────
    // Lifecycle / configuration
    // ───────────────────────────────────────────────────────────────────

    @Test
    public void supervisorBoundAtStartAndClearedAtStop() {
        SbbSupervisor supervisor = container.getSbbSupervisor();
        assertNotNull("supervisor must be bound after start()", supervisor);
        assertEquals(0L, supervisor.getRestartCount());
        assertEquals(0L, supervisor.getDeadEntityCount());

        container.stop();
        assertNull("supervisor must be cleared after stop()", container.getSbbSupervisor());
        container = null; // tearDown must not stop() twice
    }

    @Test
    public void supervisionDisabledByConfigLeavesSupervisorUnbound() {
        MicroSleeContainer off = newContainer(false, 2, 0L, 1L);
        try {
            off.start();
            assertNull("supervisor must stay null when disabled", off.getSbbSupervisor());
        } finally {
            off.stop();
        }
    }

    @Test
    public void configurationDefaults() {
        MicroSleeConfiguration defaults = MicroSleeConfiguration.defaults();
        assertTrue(defaults.isSbbSupervisionEnabled());
        assertEquals(3, defaults.getSbbRestartMaxAttempts());
        assertEquals(100L, defaults.getSbbRestartBackoffBaseMs());
        assertEquals(3_200L, defaults.getSbbRestartBackoffMaxMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsZeroRestartAttempts() {
        MicroSleeConfiguration.builder().sbbRestartMaxAttempts(0).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsBackoffCeilingBelowBase() {
        MicroSleeConfiguration.builder().sbbRestartBackoffBaseMs(100L)
                .sbbRestartBackoffMaxMs(50L).build();
    }

    // ───────────────────────────────────────────────────────────────────
    // Delivery timeout → force restart with fresh instantiation
    // ───────────────────────────────────────────────────────────────────

    @Test
    public void deliveryTimeoutForceRestartsEntityWithFreshInstance() throws Exception {
        container.registerSbbType(CountingSbb.class, CountingSbb::new);
        SimpleSbbLocalObject old = container.acquireEntity("e1", CountingSbb.class);
        assertTrue(old.awaitReady(DEADLINE_MS, TimeUnit.MILLISECONDS));
        old.getEntityState().getCmpFields().put("state", "step1");
        InMemoryActivityContext aci = container.createActivityContext("ac1");
        container.attach("ac1", old);
        Sbb oldSbbInstance = old.getSbb();

        // Simulate the router's per-SBB virtual-thread delivery timeout
        // hook (the 30s latch path calls exactly this method).
        container.getSbbSupervisor().onDeliveryTimeout("e1");
        assertTrue("supervisor must drain the restart",
                container.getSbbSupervisor().awaitQuiescence(DEADLINE_MS));

        SimpleSbbLocalObject fresh = container.getSbbLocalObject("e1");
        assertNotNull("entity must be re-registered after restart", fresh);
        assertNotSame("restart must build a NEW local object", old, fresh);
        assertNotSame("restart must build a NEW SBB instance", oldSbbInstance, fresh.getSbb());
        assertEquals(1L, container.getSbbSupervisor().getRestartCount());
        assertEquals(1L, container.getSbbSupervisor().getTimeoutKillCount());
        assertEquals(0L, container.getSbbSupervisor().getDeadEntityCount());

        assertTrue(fresh.awaitReady(DEADLINE_MS, TimeUnit.MILLISECONDS));
        assertEquals("CMP state must survive the restart",
                "step1", fresh.getEntityState().getCmpFields().get("state"));
        assertTrue("entity must be re-attached to its activity context",
                aci.getAttachedSbbs().contains(fresh));
        assertFalse("old local object must be detached",
                aci.getAttachedSbbs().contains(old));

        // The fresh entity still works: route one event through the ACI.
        CountingSbb freshSbb = (CountingSbb) fresh.getSbb();
        container.routeEvent(new TestEvent(), aci);
        assertTrue("fresh entity must receive events after restart",
                awaitTrue(() -> freshSbb.deliveries.get() >= 1));
    }

    // ───────────────────────────────────────────────────────────────────
    // Crash loop → restart ladder → DEAD + CRITICAL alarm
    // ───────────────────────────────────────────────────────────────────

    @Test
    public void crashLoopLadderEndsInDeadEntityAndCriticalAlarm() throws Exception {
        // maxAttempts=2: 2 consecutive failures → restart #1, 2 more →
        // restart #2, 2 more → DEAD (alarm + final removal).
        container.registerSbbType(ThrowingSbb.class, ThrowingSbb::new);
        SimpleSbbLocalObject obj = container.acquireEntity("die", ThrowingSbb.class);
        assertTrue(obj.awaitReady(DEADLINE_MS, TimeUnit.MILLISECONDS));
        InMemoryActivityContext aci = container.createActivityContext("die-ac");
        container.attach("die-ac", obj);

        fireEventsAwaitingRestart(container, aci, 2, 1L);
        fireEventsAwaitingRestart(container, aci, 2, 2L);

        fireEventsAwaitingDead(container, aci, 2);

        assertEquals(2L, container.getSbbSupervisor().getRestartCount());
        assertEquals(1L, container.getSbbSupervisor().getDeadEntityCount());
        assertNull("DEAD entity must be removed for good",
                container.getSbbLocalObject("die"));
        assertTrue("DEAD entity must raise a CRITICAL alarm",
                container.getAlarmFacility().snapshot()
                        .containsKey("SbbSupervisor::die"));
        assertEquals(AlarmLevel.CRITICAL,
                container.getAlarmFacility().snapshot().get("SbbSupervisor::die"));
        assertTrue(container.getSbbSupervisor().isSupervised("die"));
    }

    @Test
    public void legacySbbCannotBeRestartedBySupervisor() throws Exception {
        // registerSbb(id, instance) takes the legacy (non-pooled) path —
        // the supervisor must refuse to restart it (no factory available).
        ThrowingSbb legacy = new ThrowingSbb();
        container.registerSbb("legacy-1", legacy);
        InMemoryActivityContext aci = container.createActivityContext("legacy-ac");
        container.attach("legacy-ac", container.getSbbLocalObject("legacy-1"));

        for (int i = 0; i < 4; i++) {
            container.routeEvent(new TestEvent(), aci);
            final int expected = i + 1;
            awaitTrue(() -> legacy.failures.get() >= expected);
        }
        assertTrue("restart attempt must have been refused",
                awaitTrue(() -> container.getSbbSupervisor().getFailedRestartCount() >= 1));
        assertEquals(0L, container.getSbbSupervisor().getRestartCount());
    }

    // ───────────────────────────────────────────────────────────────────
    // Helpers / fixtures
    // ───────────────────────────────────────────────────────────────────

    private static void fireEventsAwaitingRestart(MicroSleeContainer container,
            InMemoryActivityContext aci, int count, long expectedRestarts)
            throws InterruptedException {
        for (int i = 0; i < count; i++) {
            fireOneEventAwaitProcessed(container, aci);
        }
        assertTrue("expected restart #" + expectedRestarts,
                awaitTrue(() -> container.getSbbSupervisor().getRestartCount() >= expectedRestarts));
    }

    private static void fireEventsAwaitingDead(MicroSleeContainer container,
            InMemoryActivityContext aci, int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            fireOneEventAwaitProcessed(container, aci);
        }
        assertTrue("entity must go DEAD after exhausting restart attempts",
                awaitTrue(() -> container.getSbbSupervisor().getDeadEntityCount() >= 1));
        // giveUp() removes the entity on the supervisor thread — make sure
        // the final removal has landed before the assertions read the map.
        assertTrue("DEAD entity must be removed for good (async giveUp)",
                awaitTrue(() -> container.getSbbLocalObject("die") == null));
    }

    /**
     * Fire one event to the (possibly freshly restarted) "die" entity and
     * wait until its processing has fully completed.
     *
     * <p>Pre-existing container behaviour: an SBB exception detaches the
     * entity from its activity context (see
     * {@code EventRouterGapTest.errorHandlingPolicyDetachesSbbOnException})
     * — the detach happens asynchronously AFTER the failure, so re-attaching
     * immediately would race the detach and the next event would find the
     * ACI empty. Waiting for the detach guarantees the failure has been
     * booked before we re-attach and fire the next event, exactly like a
     * real RA that re-binds after a session error callback.
     */
    private static void fireOneEventAwaitProcessed(MicroSleeContainer container,
            InMemoryActivityContext aci) throws InterruptedException {
        SimpleSbbLocalObject live = container.getSbbLocalObject("die");
        if (live == null) {
            return; // entity mid-restart or already DEAD
        }
        container.attach("die-ac", live);
        container.routeEvent(new TestEvent(), aci);
        awaitTrue(() -> !aci.getAttachedSbbs().contains(live));
    }

    /**
     * Count deliveries observed through the ACI's attached local objects.
     * The attached list changes across restarts (new local object each
     * time), so route through the ACI and observe via the supervisor.
     */
    private static boolean awaitTrue(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private static final class TestEvent implements SleeEvent {
    }

    /** Records deliveries; never throws. */
    public static final class CountingSbb implements Sbb, SleeEventHandler {
        final AtomicInteger deliveries = new AtomicInteger();

        @Override
        public void onEvent(SleeEvent event, com.microjainslee.api.ActivityContextInterface aci) {
            deliveries.incrementAndGet();
        }
    }

    /** Always throws — drives the crash-loop ladder. */
    public static final class ThrowingSbb implements Sbb, SleeEventHandler {
        final AtomicInteger failures = new AtomicInteger();

        @Override
        public void onEvent(SleeEvent event, com.microjainslee.api.ActivityContextInterface aci) {
            failures.incrementAndGet();
            throw new RuntimeException("boom-" + failures.get());
        }
    }
}
