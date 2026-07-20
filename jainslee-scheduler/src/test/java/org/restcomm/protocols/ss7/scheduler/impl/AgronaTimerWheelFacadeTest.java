/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */


package org.restcomm.protocols.ss7.scheduler.impl;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class AgronaTimerWheelFacadeTest {

    private AgronaTimerWheelFacade facade;

    @BeforeMethod
    public void setUp() {
        facade = new AgronaTimerWheelFacade();
        facade.start();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        facade.stop();
    }

    @Test
    public void scheduleRunsTaskAfterDelay() throws Exception {
        final CountDownLatch fired = new CountDownLatch(1);

        facade.schedule(fired::countDown, 50L, TimeUnit.MILLISECONDS);

        assertTrue(fired.await(2, TimeUnit.SECONDS));
        assertEquals(facade.pendingTimers(), 0);
    }

    @Test
    public void firesNoEarlierThanDeadlineAndWithinTolerance() throws Exception {
        final long delayMs = 100L;
        final CountDownLatch fired = new CountDownLatch(1);
        final AtomicLong firedAtNs = new AtomicLong();
        final long scheduledAtNs = System.nanoTime();

        facade.schedule(() -> {
            firedAtNs.set(System.nanoTime());
            fired.countDown();
        }, delayMs, TimeUnit.MILLISECONDS);

        assertTrue(fired.await(2, TimeUnit.SECONDS));

        long actualDelayMs = TimeUnit.NANOSECONDS.toMillis(firedAtNs.get() - scheduledAtNs);
        // Never early beyond one tick quantum (~1.05 ms).
        assertTrue(actualDelayMs >= delayMs - 2,
                "fired too early: " + actualDelayMs + "ms < " + delayMs + "ms");
        // Late bound is generous: tick + OS scheduling jitter on a loaded CI box.
        assertTrue(actualDelayMs <= delayMs + 50,
                "fired too late: " + actualDelayMs + "ms");
    }

    @Test
    public void cancelPreventsFiringAndReleasesTimer() throws Exception {
        final CountDownLatch fired = new CountDownLatch(1);

        Runnable cancel = facade.schedule(fired::countDown, 200L, TimeUnit.MILLISECONDS);
        assertEquals(facade.pendingTimers(), 1);

        cancel.run();

        assertEquals(facade.pendingTimers(), 0, "cancelled timer must not leak");
        assertFalse(fired.await(400, TimeUnit.MILLISECONDS), "cancelled timer must not fire");
        assertEquals(facade.cancelledTimers(), 1);
    }

    @Test
    public void cancelAfterFireIsHarmless() throws Exception {
        final CountDownLatch fired = new CountDownLatch(1);

        Runnable cancel = facade.schedule(fired::countDown, 20L, TimeUnit.MILLISECONDS);
        assertTrue(fired.await(2, TimeUnit.SECONDS));

        cancel.run();
        cancel.run();

        assertEquals(facade.cancelledTimers(), 0, "post-fire cancel must be a no-op");
        assertEquals(facade.pendingTimers(), 0);
    }

    @Test
    public void reentrantScheduleFromCallbackWorks() throws Exception {
        final CountDownLatch second = new CountDownLatch(1);

        facade.schedule(
                () -> facade.schedule(second::countDown, 20L, TimeUnit.MILLISECONDS),
                20L, TimeUnit.MILLISECONDS);

        assertTrue(second.await(2, TimeUnit.SECONDS), "callback-scheduled timer must fire");
    }

    @Test
    public void failingTaskDoesNotKillTheWheel() throws Exception {
        final CountDownLatch survivor = new CountDownLatch(1);

        facade.schedule(() -> {
            throw new RuntimeException("boom");
        }, 20L, TimeUnit.MILLISECONDS);
        facade.schedule(survivor::countDown, 60L, TimeUnit.MILLISECONDS);

        assertTrue(survivor.await(2, TimeUnit.SECONDS),
                "a throwing task must not stop subsequent timers");
    }

    @Test
    public void manyConcurrentTimersAllFireWithoutLeaks() throws Exception {
        final int count = 2_000;
        final CountDownLatch allFired = new CountDownLatch(count);
        final AtomicInteger firedTotal = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            long delay = 10 + (i % 150);
            facade.schedule(() -> {
                firedTotal.incrementAndGet();
                allFired.countDown();
            }, delay, TimeUnit.MILLISECONDS);
        }

        assertTrue(allFired.await(5, TimeUnit.SECONDS),
                "only " + firedTotal.get() + "/" + count + " timers fired");
        assertTrue(facade.awaitTermination(1, TimeUnit.SECONDS));
        assertEquals(facade.pendingTimers(), 0, "no timer entries may leak");
        assertEquals(facade.firedTimers(), count);
    }

    @Test
    public void scheduleAfterLongIdleFiresOnTime() throws Exception {
        // Let the wheel idle so currentTick lags real time, then verify the
        // wind-forward in schedule() prevents a late/early fire.
        Thread.sleep(600L);

        final CountDownLatch fired = new CountDownLatch(1);
        final long scheduledAtNs = System.nanoTime();
        final AtomicLong firedAtNs = new AtomicLong();

        facade.schedule(() -> {
            firedAtNs.set(System.nanoTime());
            fired.countDown();
        }, 50L, TimeUnit.MILLISECONDS);

        assertTrue(fired.await(2, TimeUnit.SECONDS));
        long actualDelayMs = TimeUnit.NANOSECONDS.toMillis(firedAtNs.get() - scheduledAtNs);
        assertTrue(actualDelayMs >= 48 && actualDelayMs <= 100,
                "post-idle timer fired at " + actualDelayMs + "ms (expected ~50ms)");
    }
}
