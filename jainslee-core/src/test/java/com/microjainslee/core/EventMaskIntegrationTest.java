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

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration test verifying §8.6 EventMask is actually consulted by the
 * {@link EventRouter} during dispatch.
 */
public class EventMaskIntegrationTest {

    private static final class AlphaEvent implements SleeEvent { }

    private static final class BetaEvent implements SleeEvent { }

    private static final class CountingHandler implements Sbb, SleeEventHandler {
        final CountDownLatch alphaLatch = new CountDownLatch(1);
        final CountDownLatch betaLatch = new CountDownLatch(1);
        final AtomicInteger alphaCount = new AtomicInteger();
        final AtomicInteger betaCount = new AtomicInteger();

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            if (event instanceof AlphaEvent) {
                alphaCount.incrementAndGet();
                alphaLatch.countDown();
            } else if (event instanceof BetaEvent) {
                betaCount.incrementAndGet();
                betaLatch.countDown();
            }
        }
    }

    @Test
    public void routerSkipsEventsRejectedByMask() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
                        .preferVirtualThreads(false)
                        .sbbPoolMin(2)
                        .sbbPoolMax(8)
                        .sbbPerVirtualThread(true)
                        .build());
        container.start();
        try {
            CountingHandler sbb = new CountingHandler();
            SbbLocalObject lo = container.registerSbb("sbb-mask",
                    sbb, new EventMask(AlphaEvent.class));
            InMemoryActivityContext aci = container.createActivityContext("ac-mask");
            container.attach("ac-mask", lo);

            container.routeEvent(new AlphaEvent(), aci);
            assertTrue("AlphaEvent must arrive at SBB within 5s",
                    sbb.alphaLatch.await(5, TimeUnit.SECONDS));

            container.routeEvent(new BetaEvent(), aci);
            Thread.sleep(200);
            assertEquals("BetaEvent must be skipped by mask",
                    0, sbb.betaCount.get());
            assertFalse("BetaEvent latch must not be counted down",
                    sbb.betaLatch.await(0, TimeUnit.MILLISECONDS));

            assertTrue("router must report >=1 skipped mask hit, got "
                    + container.getEventRouter().getSkippedMaskCount(),
                    container.getEventRouter().getSkippedMaskCount() >= 1);
        } finally {
            container.stop();
        }
    }

    @Test
    public void acceptAllMaskDeliversEverything() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
                        .preferVirtualThreads(false)
                        .sbbPoolMin(2)
                        .sbbPoolMax(8)
                        .sbbPerVirtualThread(true)
                        .build());
        container.start();
        try {
            CountingHandler sbb = new CountingHandler();
            SbbLocalObject lo = container.registerSbb("sbb-all", sbb);
            InMemoryActivityContext aci = container.createActivityContext("ac-all");
            container.attach("ac-all", lo);

            container.routeEvent(new AlphaEvent(), aci);
            container.routeEvent(new BetaEvent(), aci);
            assertTrue(sbb.alphaLatch.await(5, TimeUnit.SECONDS));
            assertTrue(sbb.betaLatch.await(5, TimeUnit.SECONDS));
        } finally {
            container.stop();
        }
    }

    @Test
    public void emptyMaskRejectsEverything() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
                        .preferVirtualThreads(false)
                        .sbbPoolMin(2)
                        .sbbPoolMax(8)
                        .sbbPerVirtualThread(true)
                        .build());
        container.start();
        try {
            CountingHandler sbb = new CountingHandler();
            SbbLocalObject lo = container.registerSbb("sbb-empty", sbb, EventMask.EMPTY);
            InMemoryActivityContext aci = container.createActivityContext("ac-empty");
            container.attach("ac-empty", lo);

            container.routeEvent(new AlphaEvent(), aci);
            container.routeEvent(new BetaEvent(), aci);
            Thread.sleep(200);
            assertEquals(0, sbb.alphaCount.get());
            assertEquals(0, sbb.betaCount.get());
            assertTrue(container.getEventRouter().getSkippedMaskCount() >= 2);
        } finally {
            container.stop();
        }
    }
}