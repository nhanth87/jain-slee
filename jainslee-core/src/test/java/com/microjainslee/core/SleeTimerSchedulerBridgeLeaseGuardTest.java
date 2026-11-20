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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ADR 0007 D6 — replicated absolute deadlines; firing guarded by the dialog lease.
 *
 * <p>A {@code T}-timer armed on a dead node must not die with it: the bridge
 * replicates the <b>absolute</b> deadline (epoch ms) and every node arms a
 * local timer, but only the lease owner delivers the {@code TimerFiredEvent}.
 */
public class SleeTimerSchedulerBridgeLeaseGuardTest {

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
    public void schedule_replicates_absolute_deadline_and_cancel_removes_it() {
        FakeSbb sbb = new FakeSbb();
        bridge.bindActivityContext(sbb, new NamedAci("gmlc-1"));
        long before = System.currentTimeMillis();
        long timerId = bridge.schedule(sbb, 60_000);
        long after = System.currentTimeMillis();

        TimerDeadlineStore.TimerDeadline deadline = store.get(timerId);
        assertNotNull("schedule must replicate the absolute deadline", deadline);
        assertEquals("gmlc-1", deadline.activityContextName());
        assertTrue("deadline must be absolute epoch ms",
                deadline.expiresAtEpochMs() >= before + 60_000
                        && deadline.expiresAtEpochMs() <= after + 60_000);

        bridge.cancel(timerId);
        assertNull("cancel must drop the replicated deadline", store.get(timerId));
    }

    @Test
    public void cancelAll_removes_every_replicated_deadline_of_the_sbb() {
        FakeSbb sbb = new FakeSbb();
        bridge.bindActivityContext(sbb, new NamedAci("gmlc-2"));
        long t1 = bridge.schedule(sbb, 60_000);
        long t2 = bridge.schedule(sbb, 60_000);
        assertEquals(2, store.size());

        bridge.cancelAll(sbb);
        assertNull(store.get(t1));
        assertNull(store.get(t2));
        assertEquals(0, store.size());
    }

    @Test
    public void fire_reaches_owner_but_is_dropped_for_non_owner() throws Exception {
        FakeSbb sbb = new FakeSbb();
        NamedAci aci = new NamedAci("otid:42");
        bridge.bindActivityContext(sbb, aci);

        // Survivor path: guard allows → event delivered, store entry cleared.
        bridge.setTransmitGuard(name -> true);
        bridge.schedule(sbb, 50);
        assertTrue("owner must receive the TimerFiredEvent",
                eventRouter.awaitRouted(5, TimeUnit.SECONDS));
        assertEquals(1, eventRouter.routedCount.get());

        // Zombie path: guard refuses → no delivery, counted in guardedFires.
        eventRouter.reset();
        bridge.setTransmitGuard(name -> false);
        bridge.schedule(sbb, 50);
        Thread.sleep(800);
        assertEquals("non-owner must not deliver the TimerFiredEvent",
                0, eventRouter.routedCount.get());
        assertEquals(1, bridge.guardedFires());
        assertEquals("fired timers must not linger in the store", 0, store.size());
    }

    // ----- harness -----

    /** EventRouter that counts routeEvent calls without needing attached SBBs. */
    private static final class CountingRouter extends EventRouter {
        final AtomicInteger routedCount = new AtomicInteger();
        volatile CountDownLatch latch = new CountDownLatch(1);

        CountingRouter() {
            super(16, false);
        }

        void reset() {
            routedCount.set(0);
            latch = new CountDownLatch(1);
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
                new com.microjainslee.api.SbbID("timer-guard-test-sbb");

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
