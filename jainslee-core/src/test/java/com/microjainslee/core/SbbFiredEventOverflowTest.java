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
import com.microjainslee.api.ActivityEndedEvent;
import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import org.junit.After;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SYNC delivery: an SBB that ends its activity from onEvent (an RA command such as
 * "answer BYE → end dialog") while the ring is full used to block on the ring that the
 * single worker — itself waiting on this SBB — had to drain: a 30 s latch stall per
 * occurrence. SBB-fired events now take the overflow path and keep per-activity order.
 */
public class SbbFiredEventOverflowTest {

    record Hangup(String callId) implements SleeEvent {}

    static volatile RaBootstrapPort bootstrap;
    static final Map<String, ActivityHandle> HANDLES = new ConcurrentHashMap<>();
    static final AtomicInteger HANGUPS = new AtomicInteger();
    static final AtomicInteger ENDS = new AtomicInteger();
    static final AtomicInteger ORDER_VIOLATIONS = new AtomicInteger();
    static final Map<String, Boolean> HUNG_UP = new ConcurrentHashMap<>();

    /** Ends its own activity inside onEvent, like an S-CSCF answering BYE. */
    public static class HangupSbb implements Sbb, SleeEventHandler {
        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            if (event instanceof Hangup h) {
                HANGUPS.incrementAndGet();
                HUNG_UP.put(h.callId(), Boolean.TRUE);
                bootstrap.endActivity(HANDLES.get(h.callId()));
            } else if (event instanceof ActivityEndedEvent ended) {
                if (!HUNG_UP.containsKey(ended.getHandle().getId())) {
                    ORDER_VIOLATIONS.incrementAndGet();
                }
                ENDS.incrementAndGet();
            }
        }
    }

    static final class TestRa implements RaEndpointPort, RaCommandPort {
        @Override
        public void activate(RaBootstrapPort b) {
            bootstrap = b;
        }

        @Override
        public void deactivate() {
        }

        @Override
        public String getRaName() {
            return "hangup-ra";
        }

        @Override
        public void sendCommand(OutboundCommand command) {
        }
    }

    private MicroSleeContainer container;

    @After
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }

    @Test(timeout = 20_000)
    public void sbbEndingActivitiesUnderAFullRingNeverStallsSyncDelivery() throws Exception {
        HANDLES.clear();
        HUNG_UP.clear();
        HANGUPS.set(0);
        ENDS.set(0);
        ORDER_VIOLATIONS.set(0);
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(8)
                .eventDeliveryMode(EventDeliveryMode.SYNC)
                .build());
        container.start();
        container.registerSbbType(HangupSbb.class, HangupSbb::new);
        container.createIesDispatcher();
        container.mapEventToSbb(Hangup.class, "HangupSbb");
        TestRa ra = new TestRa();
        container.registerRa(ra, ra);

        int calls = 500;
        long t0 = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            String id = "c-" + i;
            ActivityHandle h = bootstrap.createActivityHandle(id);
            HANDLES.put(id, h);
            bootstrap.fireEvent(new Hangup(id), h, null);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (ENDS.get() < calls && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals(calls, HANGUPS.get());
        assertEquals("every SBB-fired activity end delivered", calls, ENDS.get());
        assertEquals("ActivityEnded never overtakes the event that caused it", 0, ORDER_VIOLATIONS.get());
        assertTrue("no 30 s latch stall (took " + ms + " ms)", ms < 10_000);
    }
}
