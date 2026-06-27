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

import com.microjainslee.api.PoolableSbb;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.ActivityContextInterface;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AcquireReleaseEntityIntegrationTest {

    @Test
    public void pooledAcquireReleaseReusesSbbInstance() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(64)
                        .sbbPoolMin(4)
                        .sbbPoolMax(32)
                        .sbbPerVirtualThread(true)
                        .build());
        container.start();
        try {
            final AtomicInteger factoryCalls = new AtomicInteger();
            container.registerSbbType(CountingSbb.class, () -> {
                factoryCalls.incrementAndGet();
                return new CountingSbb();
            });

            SbbLocalObject first = container.acquireEntity("session-1", CountingSbb.class);
            InMemoryActivityContext aci = container.createActivityContext("session-1");
            container.attach("session-1", first);
            container.routeEvent(new SimpleEvent(), aci);
            Thread.sleep(100);
            assertEquals(1, ((CountingSbb) first.getSbb()).events.get());

            container.releaseEntity("session-1");
            Thread.sleep(50);

            SbbLocalObject second = container.acquireEntity("session-2", CountingSbb.class);
            assertEquals("factory should only run once when pool reuses instance",
                    1, factoryCalls.get());
            assertTrue(((CountingSbb) second.getSbb()).resetCount >= 1);
        } finally {
            container.stop();
        }
    }

    private static final class SimpleEvent implements SleeEvent { }

    private static final class CountingSbb implements Sbb, SleeEventHandler, PoolableSbb {
        final AtomicInteger events = new AtomicInteger();
        volatile int resetCount;

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            events.incrementAndGet();
        }

        @Override
        public void resetForReuse(String entityId) {
            resetCount++;
            events.set(0);
        }
    }
}
