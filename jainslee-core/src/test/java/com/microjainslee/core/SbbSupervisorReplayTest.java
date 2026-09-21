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
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.core.supervision.SbbSupervisor;
import com.microjainslee.core.supervision.SbbSupervisionPort;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Production P3 (M3) — parked-event replay during the supervised-restart
 * window.
 *
 * <p>Covers: events routed while an entity's supervised restart is in
 * flight are parked (never delivered to the wedged / doomed instance) and
 * re-routed to the fresh entity once the restart lands; the capacity-0
 * opt-out keeps the pre-M3 behaviour; refused restarts and DEAD entities
 * drop their parked events instead of replaying them; the per-entity
 * buffer bound; and the configuration knob.
 *
 * @author Tran Nhan (nhanth87)
 */
public class SbbSupervisorReplayTest {

    private static final long DEADLINE_MS = 5_000L;

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        // backoff 200ms gives the test a wide, deterministic restart window
        container = newContainer(3, 200L, 400L, 256);
        container.start();
    }

    @After
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    private static MicroSleeContainer newContainer(int maxAttempts, long backoffBaseMs,
            long backoffMaxMs, int replayCapacity) {
        return new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(16)
                .sbbSupervisionEnabled(true)
                .sbbRestartMaxAttempts(maxAttempts)
                .sbbRestartBackoffBaseMs(backoffBaseMs)
                .sbbRestartBackoffMaxMs(backoffMaxMs)
                .sbbRestartReplayCapacity(replayCapacity)
                .build());
    }

    // ───────────────────────────────────────────────────────────────────
    // Container integration — park, restart, replay
    // ───────────────────────────────────────────────────────────────────

    @Test
    public void parkedEventsAreReplayedToFreshInstanceAfterRestart() throws Exception {
        container.registerSbbType(CountingSbb.class, CountingSbb::new);
        SimpleSbbLocalObject old = container.acquireEntity("e1", CountingSbb.class);
        assertTrue(old.awaitReady(DEADLINE_MS, TimeUnit.MILLISECONDS));
        InMemoryActivityContext aci = container.createActivityContext("ac1");
        container.attach("ac1", old);
        CountingSbb oldSbb = (CountingSbb) old.getSbb();
        SbbSupervisor supervisor = container.getSbbSupervisor();

        // Enter the restart window deterministically: the timeout hook
        // schedules a supervised restart with a 200ms backoff.
        supervisor.onDeliveryTimeout("e1");
        assertTrue("restart must be in flight", supervisor.isRestarting("e1"));

        // Events routed while the restart is pending are parked — they
        // never reach the (wedged) old instance.
        for (int i = 0; i < 3; i++) {
            container.routeEvent(new TestEvent(), aci);
        }
        assertTrue("all three events must park inside the window",
                awaitTrue(() -> supervisor.getParkedEventCount() == 3));
        assertEquals("old instance must not receive parked events",
                0, oldSbb.deliveries.get());

        assertTrue(supervisor.awaitQuiescence(DEADLINE_MS));

        SimpleSbbLocalObject fresh = container.getSbbLocalObject("e1");
        assertNotNull("fresh entity must exist after restart", fresh);
        CountingSbb freshSbb = (CountingSbb) fresh.getSbb();
        assertTrue("replayed events must reach the fresh instance",
                awaitTrue(() -> freshSbb.deliveries.get() == 3));
        assertTrue("fresh entity must be attached to its ACI",
                aci.getAttachedSbbs().contains(fresh));
        assertFalse("old local object must be detached",
                aci.getAttachedSbbs().contains(old));
        assertEquals(3L, supervisor.getParkedEventCount());
        assertEquals(3L, supervisor.getReplayedEventCount());
        assertEquals(0L, supervisor.getDroppedEventCount());
    }

    @Test
    public void zeroReplayCapacityKeepsPreReplayBehaviour() throws Exception {
        MicroSleeContainer noReplay = newContainer(3, 200L, 400L, 0);
        try {
            noReplay.start();
            noReplay.registerSbbType(CountingSbb.class, CountingSbb::new);
            SimpleSbbLocalObject obj = noReplay.acquireEntity("e1", CountingSbb.class);
            assertTrue(obj.awaitReady(DEADLINE_MS, TimeUnit.MILLISECONDS));
            InMemoryActivityContext aci = noReplay.createActivityContext("ac1");
            noReplay.attach("ac1", obj);
            CountingSbb sbb = (CountingSbb) obj.getSbb();

            noReplay.getSbbSupervisor().onDeliveryTimeout("e1");
            noReplay.routeEvent(new TestEvent(), aci);

            // capacity 0: nothing parks — the event takes the normal
            // (pre-M3) delivery path to the still-alive entity.
            assertTrue(awaitTrue(() -> sbb.deliveries.get() >= 1));
            assertTrue(noReplay.getSbbSupervisor().awaitQuiescence(DEADLINE_MS));
            assertEquals(0L, noReplay.getSbbSupervisor().getParkedEventCount());
            assertEquals(0L, noReplay.getSbbSupervisor().getReplayedEventCount());
            assertEquals(0L, noReplay.getSbbSupervisor().getDroppedEventCount());
        } finally {
            noReplay.stop();
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Supervisor unit — refused restart / DEAD / bound (stub port)
    // ───────────────────────────────────────────────────────────────────

    @Test
    public void refusedRestartDropsParkedEvents() throws Exception {
        StubPort port = new StubPort(false);
        SbbSupervisor supervisor = new SbbSupervisor(port, 1, 200L, 200L, 16);
        try {
            supervisor.onSbbException("r1", new RuntimeException("boom"));
            assertTrue(supervisor.parkEvent("r1", new TestEvent(), null));
            assertTrue(supervisor.parkEvent("r1", new TestEvent(), null));
            assertTrue(awaitTrue(() -> supervisor.getParkedEventCount() == 2));

            assertTrue(supervisor.awaitQuiescence(DEADLINE_MS));
            assertEquals("refused restart must drop parked events",
                    2L, supervisor.getDroppedEventCount());
            assertEquals(0L, supervisor.getReplayedEventCount());
            assertTrue("no event may be replayed on refusal", port.replayed.isEmpty());
        } finally {
            supervisor.shutdown();
        }
    }

    @Test
    public void deadEntityAbandonsParkedEvents() throws Exception {
        StubPort port = new StubPort(true);
        SbbSupervisor supervisor = new SbbSupervisor(port, 1, 0L, 0L, 16);
        try {
            supervisor.onSbbException("d1", new RuntimeException("boom-1"));
            assertTrue(supervisor.awaitQuiescence(DEADLINE_MS));
            assertEquals(1L, supervisor.getRestartCount());

            assertTrue(supervisor.parkEvent("d1", new TestEvent(), null));
            assertTrue(supervisor.parkEvent("d1", new TestEvent(), null));

            // The next schedule exceeds maxAttempts=1 → giveUp → DEAD →
            // the parked events are abandoned synchronously, never
            // replayed to a poisoned instance.
            supervisor.onSbbException("d1", new RuntimeException("boom-2"));
            assertEquals(1L, supervisor.getDeadEntityCount());
            assertEquals("DEAD entity must drop its parked events",
                    2L, supervisor.getDroppedEventCount());
            assertEquals(0L, supervisor.getReplayedEventCount());
            assertTrue(supervisor.awaitQuiescence(DEADLINE_MS));
            assertEquals(1, port.giveUps.get());
        } finally {
            supervisor.shutdown();
        }
    }

    @Test
    public void replayBufferIsBoundedPerEntity() {
        StubPort port = new StubPort(true);
        SbbSupervisor supervisor = new SbbSupervisor(port, 3, 0L, 0L, 2);
        try {
            assertTrue(supervisor.parkEvent("o1", new TestEvent(), null));
            assertTrue(supervisor.parkEvent("o1", new TestEvent(), null));
            assertFalse("third event must overflow the per-entity bound",
                    supervisor.parkEvent("o1", new TestEvent(), null));
            assertEquals(2L, supervisor.getParkedEventCount());
            assertEquals(1L, supervisor.getDroppedEventCount());
        } finally {
            supervisor.shutdown();
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Configuration
    // ───────────────────────────────────────────────────────────────────

    @Test
    public void replayConfigurationDefaultsAndValidation() {
        assertEquals(256, MicroSleeConfiguration.defaults().getSbbRestartReplayCapacity());
    }

    @Test(expected = IllegalArgumentException.class)
    public void builderRejectsNegativeReplayCapacity() {
        MicroSleeConfiguration.builder().sbbRestartReplayCapacity(-1).build();
    }

    // ───────────────────────────────────────────────────────────────────
    // Helpers / fixtures
    // ───────────────────────────────────────────────────────────────────

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
    static final class CountingSbb implements Sbb, SleeEventHandler {
        final AtomicInteger deliveries = new AtomicInteger();

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            deliveries.incrementAndGet();
        }
    }

    /** Deterministic supervisor port: never touches the container. */
    private static final class StubPort implements SbbSupervisionPort {
        final AtomicInteger giveUps = new AtomicInteger();
        final List<SleeEvent> replayed = new CopyOnWriteArrayList<>();
        private final boolean allowRestart;

        StubPort(boolean allowRestart) {
            this.allowRestart = allowRestart;
        }

        @Override
        public boolean restartEntity(String sbbId) {
            return allowRestart;
        }

        @Override
        public void giveUp(String sbbId, String reason) {
            giveUps.incrementAndGet();
        }

        @Override
        public void replayEvent(SleeEvent event, ActivityContextInterface aci) {
            replayed.add(event);
        }
    }
}