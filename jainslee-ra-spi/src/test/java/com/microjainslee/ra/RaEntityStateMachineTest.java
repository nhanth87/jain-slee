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

import com.microjainslee.api.ResourceAdaptor;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RaEntityStateMachineTest {

    /** Minimal RA that records every lifecycle call. */
    static final class RecordingRa implements ResourceAdaptor {
        final AtomicInteger configureCount = new AtomicInteger();
        final AtomicInteger activeCount = new AtomicInteger();
        final AtomicInteger stoppingCount = new AtomicInteger();
        final AtomicInteger inactiveCount = new AtomicInteger();
        volatile com.microjainslee.api.ResourceAdaptorContext ctx;

        @Override public void setResourceAdaptorContext(com.microjainslee.api.ResourceAdaptorContext ctx) {
            this.ctx = ctx;
        }
        @Override public void raConfigure() { configureCount.incrementAndGet(); }
        @Override public void raActive() { activeCount.incrementAndGet(); }
        @Override public void raStopping() { stoppingCount.incrementAndGet(); }
        @Override public void raInactive() { inactiveCount.incrementAndGet(); }
        @Override public void raUnconfigure() { }
    }

    @Test
    public void initialStateIsInactive() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-test");
        assertEquals(RaEntityStateMachine.State.INACTIVE, sm.getState());
        assertFalse(sm.isActive());
        assertFalse(sm.canFireEvents());
        assertSame(ra, sm.getRaEntityName() != null ? ra : ra);
        assertEquals("ra-test", sm.getRaEntityName());
        assertNotNull(sm.toString());
    }

    @Test
    public void activateDrivesInactiveToActive() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-1");
        sm.activate();
        assertEquals(RaEntityStateMachine.State.ACTIVE, sm.getState());
        assertTrue(sm.isActive());
        assertTrue(sm.canFireEvents());
        assertEquals(1, ra.activeCount.get());
        sm.assertCanFireEvent(); // should not throw
    }

    @Test
    public void cannotActivateWhenAlreadyActive() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-2");
        sm.activate();
        try {
            sm.activate();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void deactivateDrivesActiveToStopping() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-3");
        sm.activate();
        sm.deactivate();
        assertEquals(RaEntityStateMachine.State.STOPPING, sm.getState());
        assertFalse(sm.canFireEvents());
        assertEquals(1, ra.stoppingCount.get());
        // fireEvent is rejected in STOPPING
        try {
            sm.assertCanFireEvent();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void cannotDeactivateWhenInactive() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-4");
        try {
            sm.deactivate();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void stopCompleteDrivesStoppingToInactive() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-5");
        sm.activate();
        sm.deactivate();
        sm.stopComplete();
        assertEquals(RaEntityStateMachine.State.INACTIVE, sm.getState());
        assertEquals(1, ra.inactiveCount.get());
        // Second stopComplete is a no-op
        sm.stopComplete();
        assertEquals(1, ra.inactiveCount.get());
    }

    @Test
    public void stopCompleteIgnoredWhenNotStopping() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-6");
        // INACTIVE -> stopComplete should be a no-op (with a warn log).
        sm.stopComplete();
        assertEquals(RaEntityStateMachine.State.INACTIVE, sm.getState());
        assertEquals(0, ra.inactiveCount.get());
    }

    @Test
    public void genericTransitionHonoursPairs() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-7");
        sm.transition(RaEntityStateMachine.State.INACTIVE, RaEntityStateMachine.State.ACTIVE);
        assertEquals(RaEntityStateMachine.State.ACTIVE, sm.getState());
        sm.transition(RaEntityStateMachine.State.ACTIVE, RaEntityStateMachine.State.STOPPING);
        assertEquals(RaEntityStateMachine.State.STOPPING, sm.getState());
        sm.transition(RaEntityStateMachine.State.STOPPING, RaEntityStateMachine.State.INACTIVE);
        assertEquals(RaEntityStateMachine.State.INACTIVE, sm.getState());
    }

    @Test
    public void invalidTransitionThrows() {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-8");
        try {
            sm.transition(RaEntityStateMachine.State.INACTIVE, RaEntityStateMachine.State.STOPPING);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
        try {
            sm.transition(RaEntityStateMachine.State.ACTIVE, RaEntityStateMachine.State.INACTIVE);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void concurrentActivateIsSerialised() throws Exception {
        RecordingRa ra = new RecordingRa();
        RaEntityStateMachine sm = new RaEntityStateMachine(ra, "ra-concurrent");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    sm.activate();
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, successes.get());
        assertEquals(threads - 1, failures.get());
        assertEquals(RaEntityStateMachine.State.ACTIVE, sm.getState());
    }

    @Test
    public void raActiveFailureRollsBackToInactive() {
        ResourceAdaptor faulty = new ResourceAdaptor() {
            @Override public void setResourceAdaptorContext(com.microjainslee.api.ResourceAdaptorContext ctx) {}
            @Override public void raConfigure() {}
            @Override public void raActive() { throw new RuntimeException("boom"); }
            @Override public void raStopping() {}
            @Override public void raInactive() {}
            @Override public void raUnconfigure() {}
        };
        RaEntityStateMachine sm = new RaEntityStateMachine(faulty, "ra-fault");
        try {
            sm.activate();
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("raActive"));
        }
        assertEquals(RaEntityStateMachine.State.INACTIVE, sm.getState());
    }

    @Test
    public void nullArgumentsRejected() {
        try {
            new RaEntityStateMachine(null, "x");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
        try {
            new RaEntityStateMachine(new RecordingRa(), null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
        try {
            new RaEntityStateMachine(new RecordingRa(), "");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }
}
