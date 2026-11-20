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
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ADR 0007 D6 — deadline-replication edge cases on the timer bridge.
 *
 * <p>Complements {@link SleeTimerSchedulerBridgeLeaseGuardTest}: zero-delay
 * firing, cancel-before-fire, the no-cluster ({@code null} store) path, the
 * lease-guard allow/refuse asymmetry, and {@code cancelAll} replication
 * cleanup. All waits are latch/condition based — no fixed sleep where an
 * await is available.
 */
public class SleeTimerSchedulerBridgeDeadlineEdgeTest {

    private CountingRouter eventRouter;
    private SleeTimerSchedulerBridge bridge;
    private InMemoryTimerDeadlineStore store;

    @Before
    public void setUp() {
        eventRouter = new CountingRouter();
        bridge = SleeTimerSchedulerBridge.create(eventRouter);
        store = new InMemoryTimerDeadlineStore();
        bridge.setDeadlineStore(store);
    }

    @After
    public void tearDown() {
        if (bridge != null) {
            bridge.shutdown();
        }
        if (eventRouter != null) {
            eventRouter.shutdown();
        }
    }

    @Test
    public void zeroDelayTimerFiresAndClearsReplicatedDeadline() throws Exception {
        FakeSbb sbb = new FakeSbb();
        bridge.bindActivityContext(sbb, new NamedAci("zero-delay"));
        long timerId = bridge.schedule(sbb, 0);

        assertTrue("delay-0 timer must fire", eventRouter.awaitRouted(5, TimeUnit.SECONDS));
        assertEquals(1, eventRouter.routedCount.get());
        assertNull("a fired timer must not linger in the store", store.get(timerId));
        assertEquals(0, store.size());
    }

    @Test
    public void cancelBeforeFireDeliversNothingAndClearsStore() throws Exception {
        FakeSbb sbb = new FakeSbb();
        bridge.bindActivityContext(sbb, new NamedAci("cancel-me"));
        long timerId = bridge.schedule(sbb, 60_000);
        assertNotNull(store.get(timerId));

        bridge.cancel(timerId);
        assertNull(store.get(timerId));
        assertEquals(0, store.size());

        assertFalse("cancelled timer must not deliver an event",
                eventRouter.awaitRouted(500, TimeUnit.MILLISECONDS));
        assertEquals(0, eventRouter.routedCount.get());
    }

    @Test
    public void nullDeadlineStoreStillFiresAndReplicatesNothing() throws Exception {
        FakeSbb sbb = new FakeSbb();
        bridge.bindActivityContext(sbb, new NamedAci("no-store"));
        bridge.setDeadlineStore(null);

        bridge.schedule(sbb, 0);
        assertTrue("local wheel must fire with no cluster store bound",
                eventRouter.awaitRouted(5, TimeUnit.SECONDS));
        assertEquals(1, eventRouter.routedCount.get());
        assertEquals("null store must replicate nothing", 0, store.size());
    }

    @Test
    public void transmitGuardRefusalDropsEventAndClearsStore() throws Exception {
        FakeSbb sbb = new FakeSbb();
        bridge.bindActivityContext(sbb, new NamedAci("zombie"));
        final CountDownLatch guardInvoked = new CountDownLatch(1);
        bridge.setTransmitGuard(name -> {
            guardInvoked.countDown();
            return false;
        });
        long timerId = bridge.schedule(sbb, 0);

        assertTrue("guard must be consulted on fire", guardInvoked.await(5, TimeUnit.SECONDS));
        awaitGuardedFires(1L);

        assertFalse("guard refusal must not deliver the TimerFiredEvent",
                eventRouter.awaitRouted(250, TimeUnit.MILLISECONDS));
        assertEquals(0, eventRouter.routedCount.get());
        assertNull("guarded fire must clear the replicated deadline", store.get(timerId));
        assertEquals(0, store.size());
    }

    @Test
    public void transmitGuardAllowanceDeliversEventWithoutGuardedFire() throws Exception {
        FakeSbb sbb = new FakeSbb();
        bridge.bindActivityContext(sbb, new NamedAci("owner"));
        bridge.setTransmitGuard(name -> true);
        long timerId = bridge.schedule(sbb, 0);

        assertTrue("owner must receive the TimerFiredEvent",
                eventRouter.awaitRouted(5, TimeUnit.SECONDS));
        assertEquals(1, eventRouter.routedCount.get());
        assertEquals("allowed fire must not be counted as guarded", 0L, bridge.guardedFires());
        assertNull(store.get(timerId));
        assertEquals(0, store.size());
    }

    @Test
    public void cancelAllRemovesEveryReplicatedDeadline() {
        FakeSbb sbb = new FakeSbb();
        bridge.bindActivityContext(sbb, new NamedAci("cancel-all"));
        long t1 = bridge.schedule(sbb, 60_000);
        long t2 = bridge.schedule(sbb, 60_000);
        assertEquals(2, store.size());

        bridge.cancelAll(sbb);

        assertNull(store.get(t1));
        assertNull(store.get(t2));
        assertEquals(0, store.size());
    }

    /**
     * The guard counter is incremented by the wheel thread just after the
     * predicate returns; no latch covers it, so await the value with a bound.
     */
    private void awaitGuardedFires(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bridge.guardedFires() != expected) {
            if (System.nanoTime() > deadline) {
                fail("guardedFires did not reach " + expected + " within 5s");
            }
            Thread.sleep(5L);
        }
    }

    // ----- harness (same shape as SleeTimerSchedulerBridgeLeaseGuardTest) -----

    /** EventRouter that counts routeEvent calls without needing attached SBBs. */
    private static final class CountingRouter extends EventRouter {
        final AtomicInteger routedCount = new AtomicInteger();
        volatile CountDownLatch latch = new CountDownLatch(1);

        CountingRouter() {
            super(16, false);
        }

        boolean awaitRouted(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        @Override
        public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
            routedCount.incrementAndGet();
            latch.countDown();
        }
    }

    private static final class NamedAci implements ActivityContextInterface {
        private final String name;

        NamedAci(String name) {
            this.name = name;
        }

        @Override
        public String getActivityContextName() {
            return name;
        }

        @Override
        public void attach(SbbLocalObject sbbLocalObject) {
        }

        @Override
        public void detach(SbbLocalObject sbbLocalObject) {
        }
    }

    private static final class FakeSbb implements SbbLocalObject {
        private final com.microjainslee.api.SbbID id =
                new com.microjainslee.api.SbbID("timer-deadline-edge-test-sbb");

        @Override
        public com.microjainslee.api.Sbb getSbb() {
            return new com.microjainslee.api.Sbb() {
            };
        }

        @Override
        public com.microjainslee.api.SbbID getSbbID() {
            return id;
        }

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public void setPriority(int priority) {
        }

        @Override
        public void remove() {
        }

        @Override
        public boolean isRemoved() {
            return false;
        }
    }
}
