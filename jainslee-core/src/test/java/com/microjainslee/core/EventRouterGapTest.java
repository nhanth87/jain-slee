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
import com.microjainslee.api.InitialEventSelector;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EventRouterGapTest {

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
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
    public void dispatchesAttachedSbbsByPriorityDescending() throws Exception {
        final List<String> deliveryOrder = Collections.synchronizedList(new ArrayList<String>());
        OrderRecordingSbb low = new OrderRecordingSbb("low", deliveryOrder);
        OrderRecordingSbb high = new OrderRecordingSbb("high", deliveryOrder);
        SimpleSbbLocalObject lowLocal = container.registerSbb("low-priority", low);
        SimpleSbbLocalObject highLocal = container.registerSbb("high-priority", high);
        lowLocal.setPriority(1);
        highLocal.setPriority(9);

        InMemoryActivityContext aci = container.createActivityContext("priority-ac");
        container.attach("priority-ac", lowLocal);
        container.attach("priority-ac", highLocal);

        container.routeEvent(new TestEvent(), aci);

        assertTrue(awaitDeliveryOrder(deliveryOrder, 2));
        assertEquals("high", deliveryOrder.get(0));
        assertEquals("low", deliveryOrder.get(1));
    }

    private static boolean awaitDeliveryOrder(List<String> deliveryOrder, int expectedSize)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (deliveryOrder.size() >= expectedSize) {
                return true;
            }
            Thread.sleep(10L);
        }
        return deliveryOrder.size() >= expectedSize;
    }

    @Test
    public void initialEventSelectorAttachesRootSbbWhenAcIsEmpty() throws Exception {
        RecordingSbb root = new RecordingSbb();
        container.registerSbb("root-sbb", root);
        InMemoryActivityContext aci = container.createActivityContext("ies-ac");

        container.routeEvent(new TestEvent(), aci);

        assertTrue(root.awaitEvent());
        assertEquals(1, root.events);
        assertEquals(1, aci.getAttachedSbbs().size());
    }

    @Test
    public void initialEventSelectorRespectsCustomizerRootSelection() throws Exception {
        RecordingSbb first = new RecordingSbb();
        RecordingSbb second = new RecordingSbb();
        container.registerSbb("first-root", first);
        container.registerSbb("second-root", second);
        container.setInitialEventSelectorCustomizer(new InitialEventSelectorCustomizer() {
            @Override
            public void customize(InitialEventSelector selector) {
                selector.setRootSbbId("second-root");
            }
        });
        InMemoryActivityContext aci = container.createActivityContext("ies-select-ac");

        container.routeEvent(new TestEvent(), aci);

        assertFalse(first.awaitEvent(500, TimeUnit.MILLISECONDS));
        assertTrue(second.awaitEvent());
        assertEquals(1, aci.getAttachedSbbs().size());
        assertEquals("second-root", aci.getAttachedSbbs().get(0).getSbbID().getId());
    }

    @Test
    public void initialEventSelectorSkipsAttachWhenNotInitial() throws Exception {
        RecordingSbb root = new RecordingSbb();
        container.registerSbb("non-initial-root", root);
        container.setInitialEventSelectorCustomizer(new InitialEventSelectorCustomizer() {
            @Override
            public void customize(InitialEventSelector selector) {
                selector.setInitialEvent(false);
            }
        });
        InMemoryActivityContext aci = container.createActivityContext("ies-skip-ac");

        container.routeEvent(new TestEvent(), aci);

        assertFalse(root.awaitEvent(500, TimeUnit.MILLISECONDS));
        assertTrue(aci.getAttachedSbbs().isEmpty());
    }

    @Test
    public void errorHandlingPolicyDetachesSbbOnException() throws Exception {
        FailingSbb failing = new FailingSbb();
        SimpleSbbLocalObject localObject = container.registerSbb("failing-sbb", failing);
        InMemoryActivityContext aci = container.createActivityContext("rollback-ac");
        container.attach("rollback-ac", localObject);
        assertEquals(1, aci.getAttachedSbbs().size());

        container.routeEvent(new TestEvent(), aci);

        assertTrue(failing.awaitFailure());
        assertTrue(awaitDetached(aci, localObject));
        assertTrue(awaitExceptionHandled(failing));
    }

    private static boolean awaitExceptionHandled(FailingSbb failing)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (failing.exceptionHandled.get()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return failing.exceptionHandled.get();
    }

    private static boolean awaitDetached(InMemoryActivityContext aci, SbbLocalObject localObject)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!aci.getAttachedSbbs().contains(localObject)) {
                return true;
            }
            Thread.sleep(10L);
        }
        return !aci.getAttachedSbbs().contains(localObject);
    }

    @Test
    public void suspendedActivityContextSkipsDispatch() throws Exception {
        RecordingSbb sbb = new RecordingSbb();
        SimpleSbbLocalObject localObject = container.registerSbb("suspend-sbb", sbb);
        InMemoryActivityContext aci = container.createActivityContext("suspend-ac");
        container.attach("suspend-ac", localObject);
        aci.suspend();

        container.routeEvent(new TestEvent(), aci);

        assertFalse(sbb.awaitEvent(500, TimeUnit.MILLISECONDS));
        assertEquals(0, sbb.events);
    }

    @Test
    public void resumeAllowsDispatchAfterSuspend() throws Exception {
        RecordingSbb sbb = new RecordingSbb();
        SimpleSbbLocalObject localObject = container.registerSbb("resume-sbb", sbb);
        InMemoryActivityContext aci = container.createActivityContext("resume-ac");
        container.attach("resume-ac", localObject);
        aci.suspend();
        container.routeEvent(new TestEvent(), aci);
        Thread.sleep(200L);
        assertEquals(0, sbb.events);
        aci.resume();

        container.routeEvent(new TestEvent(), aci);

        assertTrue(sbb.awaitEvent());
        assertEquals(1, sbb.events);
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
            return awaitEvent(5, TimeUnit.SECONDS);
        }

        private boolean awaitEvent(long timeout, TimeUnit unit) throws InterruptedException {
            return eventLatch.await(timeout, unit);
        }
    }

    private static final class OrderRecordingSbb implements Sbb, SleeEventHandler {
        private final String name;
        private final List<String> deliveryOrder;

        private OrderRecordingSbb(String name, List<String> deliveryOrder) {
            this.name = name;
            this.deliveryOrder = deliveryOrder;
        }

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            deliveryOrder.add(name);
        }
    }

    private static final class FailingSbb implements Sbb, SleeEventHandler {
        private final CountDownLatch failureLatch = new CountDownLatch(1);
        private final AtomicBoolean exceptionHandled = new AtomicBoolean(false);

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            failureLatch.countDown();
            throw new RuntimeException("boom");
        }

        @Override
        public void sbbExceptionThrown(Exception e, Object event, ActivityContextInterface aci) {
            exceptionHandled.set(true);
        }

        private boolean awaitFailure() throws InterruptedException {
            return failureLatch.await(5, TimeUnit.SECONDS);
        }
    }
}
