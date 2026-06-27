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

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * High-volume stress test for JSR-240 §8.6 {@link EventMask} filtering.
 *
 * <p>Per audit G1: routing every event to every attached SBB is the
 * single biggest hot-loop waste. This test verifies the mask filter
 * by attaching 1000 SBBs to one activity context, where 100 of them
 * have a mask that explicitly excludes the event type. The expected
 * delivery count is exactly 900 per event \u00d7 burst \u2014 the masked
 * 100 must not receive the event, even though they are attached.
 */
public class EventMaskStressTest {

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(2048)
                        .preferVirtualThreads(false)
                        .sbbPerVirtualThread(false)
                        .build());
        container.start();
    }

    @After
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    public void maskedSbbsAreSkippedExactly() throws Exception {
        final int totalSbbs = 1000;
        final int maskedSbbs = 100;
        final int eventBurst = 10;
        final InMemoryActivityContext aci = container.createActivityContext("mask-stress-ac");

        final CountingSbb[] sbbs = new CountingSbb[totalSbbs];
        for (int i = 0; i < totalSbbs; i++) {
            sbbs[i] = new CountingSbb();
            sbbs[i].localObject = i < maskedSbbs
                    // Mask the first N SBBs to reject AlphaEvent.
                    ? container.registerSbb("mask-stress-sbb-m-" + i, sbbs[i], EventMask.EMPTY)
                    : container.registerSbb("mask-stress-sbb-a-" + i, sbbs[i],
                            new EventMask(AlphaEvent.class));
            container.attach("mask-stress-ac", sbbs[i].localObject);
        }

        for (int round = 0; round < eventBurst; round++) {
            container.routeEvent(new AlphaEvent(), aci);
        }

        // Wait for the Disruptor to drain.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int sum = 0;
            for (CountingSbb s : sbbs) sum += s.events;
            if (sum >= (totalSbbs - maskedSbbs) * eventBurst) break;
            Thread.sleep(20L);
        }

        long maskedReceived = 0;
        long acceptingReceived = 0;
        for (int i = 0; i < totalSbbs; i++) {
            if (i < maskedSbbs) maskedReceived += sbbs[i].events;
            else acceptingReceived += sbbs[i].events;
        }

        assertEquals("masked SBBs must receive 0 events", 0, maskedReceived);
        assertEquals("accepting SBBs each got every burst",
                (totalSbbs - maskedSbbs) * eventBurst, acceptingReceived);

        EventRouter router = container.getEventRouter();
        assertTrue("router skipped >= " + (maskedSbbs * eventBurst) + " events, got "
                        + router.getSkippedMaskCount(),
                router.getSkippedMaskCount() >= maskedSbbs * eventBurst);
    }

    @Test
    public void emptyMaskIsStrictReject() throws Exception {
        final InMemoryActivityContext aci = container.createActivityContext("empty-mask-ac");
        CountingSbb sbb = new CountingSbb();
        sbb.localObject = container.registerSbb("empty-mask-sbb", sbb, EventMask.EMPTY);
        container.attach("empty-mask-ac", sbb.localObject);

        for (int i = 0; i < 5; i++) {
            container.routeEvent(new AlphaEvent(), aci);
        }
        Thread.sleep(500L);

        assertEquals("empty-mask SBB must receive 0 events", 0, sbb.events);
        assertTrue("router skipped at least 5 events",
                container.getEventRouter().getSkippedMaskCount() >= 5);
    }

    @Test
    public void mixedMasksRouteOnlyMatchingOnes() throws Exception {
        final InMemoryActivityContext aci = container.createActivityContext("mixed-mask-ac");

        CountingSbb alphaSbb = new CountingSbb();
        CountingSbb betaSbb = new CountingSbb();
        alphaSbb.localObject = container.registerSbb("mixed-alpha", alphaSbb,
                new EventMask(AlphaEvent.class));
        betaSbb.localObject = container.registerSbb("mixed-beta", betaSbb,
                new EventMask(BetaEvent.class));
        container.attach("mixed-mask-ac", alphaSbb.localObject);
        container.attach("mixed-mask-ac", betaSbb.localObject);

        for (int i = 0; i < 10; i++) container.routeEvent(new AlphaEvent(), aci);
        for (int i = 0; i < 5; i++) container.routeEvent(new BetaEvent(), aci);

        Thread.sleep(500L);

        assertEquals("alpha got 10", 10, alphaSbb.events);
        assertEquals("beta got 5", 5, betaSbb.events);
    }

    private static final class AlphaEvent implements SleeEvent {}
    private static final class BetaEvent implements SleeEvent {}

    private static final class CountingSbb implements Sbb, SleeEventHandler {
        SbbLocalObject localObject;
        int events;

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            events++;
        }
    }
}
