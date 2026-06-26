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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MicroSleeContainerTest {

    @Test
    public void routesEventsToAttachedSbbWithoutJbossRuntime() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
                        .preferVirtualThreads(false)
                        .build());
        container.start();
        try {
            RecordingSbb sbb = new RecordingSbb();
            SbbLocalObject localObject = container.registerSbb("test-sbb", sbb);
            InMemoryActivityContext aci = container.createActivityContext("activity-1");
            container.attach("activity-1", localObject);

            container.routeEvent(new TestEvent(), aci);

            assertTrue(sbb.awaitEvent());
            assertEquals(1, sbb.events);
        } finally {
            container.stop();
        }
    }

    private static final class TestEvent implements SleeEvent {
    }

    private static final class RecordingSbb implements Sbb, SleeEventHandler {
        private final CountDownLatch eventLatch = new CountDownLatch(1);
        private int events;

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            events++;
            eventLatch.countDown();
        }

        private boolean awaitEvent() throws InterruptedException {
            return eventLatch.await(5, TimeUnit.SECONDS);
        }
    }
}
