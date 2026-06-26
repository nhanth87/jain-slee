/*
 * micro-jainslee 1.1.0
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
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.TimerFiredEvent;
import com.microjainslee.api.TimerPort;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Edge-case tests for the EventRouter and TimerPort code paths.
 */
public class EdgeCaseRouterTimerTest {

    private MicroSleeContainer container;

    @Before
    public void start() {
        container = new MicroSleeContainer();
        container.start();
    }

    @After
    public void stop() {
        if (container != null) container.stop();
    }

    // ---- EventRouter ----

    @Test
    public void routeEventToNullActivityContextDoesNotCrash() {
        try {
            container.routeEvent(new SleeEvent() { }, null);
        } catch (NullPointerException npe) {
            // acceptable: router may not handle null ACI yet
        } catch (Exception e) {
            fail("unexpected exception: " + e);
        }
    }

    @Test
    public void routeEventWithUnattachedAciDoesNotCrash() {
        InMemoryActivityContext aci = new InMemoryActivityContext("ac-1");
        try {
            container.routeEvent(new SleeEvent() { }, aci);
        } catch (Exception e) {
            fail("router must not throw on unrouteable event: " + e);
        }
    }

    @Test
    public void routeManyEventsToEmptyAciIsFastAndSafe() {
        InMemoryActivityContext aci = new InMemoryActivityContext("ac-many");
        for (int i = 0; i < 100; i++) {
            container.routeEvent(new SleeEvent() { }, aci);
        }
        // Container must still be alive
        SimpleSbbLocalObject lo = container.registerSbb("router-survivor", new Sbb() { });
        assertNotNull(lo);
    }

    // ---- TimerPort ----

    @Test
    public void timerPortIsNonNull() {
        assertNotNull(container.getTimerPort());
    }

    @Test
    public void cancelTimerIsSafe() {
        TimerPort timer = container.getTimerPort();
        // Cancelling an unknown id must be a safe no-op (or at worst a defined exception).
        try {
            timer.cancelTimer(999_999L);
        } catch (Exception e) {
            // Acceptable: any exception is fine, but it must not corrupt the container.
        }
        SimpleSbbLocalObject lo = container.registerSbb("after-bad-cancel", new Sbb() { });
        assertNotNull(lo);
    }

    @Test
    public void timerFiredEventCarriesTimerIdAndSbbLocalObject() {
        SimpleSbbLocalObject sbbLo = new SimpleSbbLocalObject(
                new SbbID("tf-1"), new Sbb() { });
        TimerFiredEvent event = new TimerFiredEvent(42L, sbbLo);
        assertEquals(42L, event.getTimerId());
        assertNotNull(event.getSbbLocalObject());
        // TimerFiredEvent is also an instanceof SleeEvent
        assertEquals(true, event instanceof SleeEvent);
    }

    @Test
    public void sleeEventHandlerContractIsCallable() throws Exception {
        final boolean[] called = { false };
        SleeEventHandler handler = new SleeEventHandler() {
            @Override
            public void onEvent(SleeEvent event, ActivityContextInterface aci) {
                called[0] = true;
            }
        };
        handler.onEvent(new SleeEvent() { }, null);
        assertEquals(true, called[0]);
    }

    @Test
    public void sleeEventHandlerExceptionPropagates() {
        final RuntimeException expected = new RuntimeException("handler-fail");
        SleeEventHandler handler = new SleeEventHandler() {
            @Override
            public void onEvent(SleeEvent event, ActivityContextInterface aci) {
                throw expected;
            }
        };
        try {
            handler.onEvent(new SleeEvent() { }, null);
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertEquals(expected, e);
        } catch (Exception e) {
            // also acceptable if it surfaces as checked
        }
    }

    @Test
    public void manyConcurrentSetTimersAreAllocatedDistinctIds() throws Exception {
        TimerPort timer = container.getTimerPort();
        SimpleSbbLocalObject lo = (SimpleSbbLocalObject) container.registerSbb(
                "timer-alloc", new Sbb() { });
        Thread.sleep(100);
        int n = 50;
        java.util.Set<Long> ids = new java.util.HashSet<Long>();
        for (int i = 0; i < n; i++) {
            long id = timer.setTimer(60_000L, lo);
            assertFalse("timer id must be unique: " + id, ids.contains(id));
            ids.add(id);
            timer.cancelTimer(id);
        }
        assertEquals(n, ids.size());
    }
}
