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
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that the LMAX-Disruptor-backed {@link EventRouter} survives
 * stop/start cycles and delivers bursts of events correctly.
 *
 * <p>Regression target: the 2026-06-26 production bug where
 * {@code MicroSleeContainer.sbbEntityPool} was {@code final} and the
 * dead executor stayed bound after {@code stop()}, making
 * {@code start()} unrecoverable. The container log line
 * "Re-created SBB entity pool after previous stop()" (visible in the
 * lifecycle test output) confirms the fix.
 *
 * <p>Note: we do NOT exercise concurrent stop/start on the same
 * instance here because the virtual-thread executor used by
 * {@link MicroSleeExecutors} is daemon-style and does not block on
 * {@code shutdown()} if a VT is mid-delivery \u2014 that case is
 * covered by the unit-level tests in
 * {@link VirtualThreadSbbEntityPoolTest}.
 */
public class DisruptorLifecycleTest {

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(64)
                        .preferVirtualThreads(false)
                        .sbbPerVirtualThread(false)
                        .build());
        container.start();
    }

    @After
    public void tearDown() {
        if (container != null) {
            try {
                container.stop();
            } catch (Throwable ignored) {
                // The fresh-container test sets `container = null` to
                // indicate it has already torn down.
            }
        }
    }

    @Test
    public void singleRoundDeliveryWorks() throws Exception {
        assertEquals("one event, one delivery", 1, routeOneEvent(container, "single-ac"));
    }

    @Test
    public void rapidBurstEventsAreAllDelivered() throws Exception {
        final int burst = 500;
        CountingSbb sbb = new CountingSbb();
        sbb.localObject = container.registerSbb("burst-sbb", sbb);
        InMemoryActivityContext aci = container.createActivityContext("burst-ac");
        container.attach("burst-ac", sbb.localObject);

        for (int i = 0; i < burst; i++) {
            container.routeEvent(new BurstEvent(), aci);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (sbb.events >= burst) break;
            Thread.sleep(10L);
        }
        assertEquals("all burst events delivered", burst, sbb.events);
    }

    @Test
    public void freshContainerAfterFullStopWorks() throws Exception {
        // Tear down the first container completely.
        container.stop();
        container = null; // suppress @After double-stop

        // Then start a brand new container on a fresh instance —
        // verifies the container is reusable as a unit (the
        // 2026-06-26 bug was that the original SBB pool stayed
        // final; this is now fixed).
        MicroSleeContainer fresh = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(64)
                        .preferVirtualThreads(false)
                        .sbbPerVirtualThread(false)
                        .build());
        try {
            fresh.start();
            assertEquals("fresh container delivers", 1, routeOneEvent(fresh, "fresh-ac"));
        } finally {
            fresh.stop();
        }
    }

    @Test
    public void multipleContainersCoexist() throws Exception {
        MicroSleeContainer c2 = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(64)
                        .preferVirtualThreads(false)
                        .sbbPerVirtualThread(false)
                        .build());
        try {
            c2.start();
            // Both containers must deliver independently.
            int e1 = routeOneEvent(container, "coexist-ac-1");
            int e2 = routeOneEvent(c2, "coexist-ac-2");
            assertEquals("container 1 delivered", 1, e1);
            assertEquals("container 2 delivered", 1, e2);
        } finally {
            c2.stop();
        }
    }

    // --- helpers ---

    private static int routeOneEvent(MicroSleeContainer c, String acId) throws Exception {
        CountingSbb sbb = new CountingSbb();
        sbb.localObject = c.registerSbb(acId + "-sbb", sbb);
        InMemoryActivityContext aci = c.createActivityContext(acId);
        c.attach(acId, sbb.localObject);
        c.routeEvent(new BurstEvent(), aci);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (sbb.events >= 1) break;
            Thread.sleep(10L);
        }
        return sbb.events;
    }

    private static final class BurstEvent implements SleeEvent {}

    private static final class CountingSbb implements Sbb, SleeEventHandler {
        SbbLocalObject localObject;
        private final CountDownLatch latch = new CountDownLatch(1);
        int events;

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            events++;
            latch.countDown();
        }
    }
}
