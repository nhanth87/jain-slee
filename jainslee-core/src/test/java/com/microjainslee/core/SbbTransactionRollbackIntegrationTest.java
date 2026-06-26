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
import com.microjainslee.api.ServiceID;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SbbTransactionRollbackIntegrationTest {

    @Test
    public void eventRouterRollsBackAttachOnSbbException() throws Exception {
        MicroSleeConfiguration configuration = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(16)
                .preferVirtualThreads(false)
                .build();
        MicroSleeContainer container = new MicroSleeContainer(configuration);
        container.start();
        try {
            AttachingFailureSbb failingSbb = new AttachingFailureSbb();
            SbbLocalObject failingLocal = container.registerSbb("failing", failingSbb);
            InMemoryActivityContext aci = container.createActivityContext("rollback-ac");
            container.attach("rollback-ac", failingLocal);

            container.routeEvent(new TestEvent(), aci);

            assertTrue(failingSbb.awaitHandled());
            assertFalse("attach during failed event must be rolled back",
                    aci.getAttachedSbbs().contains(failingSbb.attachedLocal));
        } finally {
            container.stop();
        }
    }

    @Test
    public void registeredSbbContextExposesServiceId() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            ServiceID serviceID = new ServiceID("menu", "com.example", "2.1");
            ContextCapturingSbb sbb = new ContextCapturingSbb();
            container.registerSbb("menu-sbb", sbb, serviceID);

            assertTrue(sbb.awaitContext());
            assertNotNull(sbb.context);
            assertNotNull(sbb.context.getService());
            assertTrue(serviceID.equals(sbb.context.getService()));
            assertNotNull(sbb.context.getSbbLocalObject());
        } finally {
            container.stop();
        }
    }

    private static final class TestEvent implements SleeEvent {
    }

    private static final class AttachingFailureSbb implements Sbb, SleeEventHandler {
        private final CountDownLatch handled = new CountDownLatch(1);
        private SbbLocalObject attachedLocal;

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            attachedLocal = new SimpleSbbLocalObject(new com.microjainslee.api.SbbID("child"), new Sbb() { });
            aci.attach(attachedLocal);
            throw new RuntimeException("force rollback");
        }

        private boolean awaitHandled() throws InterruptedException {
            return handled.await(5, TimeUnit.SECONDS);
        }

        @Override
        public void sbbExceptionThrown(Exception e, Object event, ActivityContextInterface aci) {
            handled.countDown();
        }
    }

    private static final class ContextCapturingSbb implements Sbb {
        private final CountDownLatch contextLatch = new CountDownLatch(1);
        private com.microjainslee.api.SbbContext context;

        @Override
        public void setSbbContext(com.microjainslee.api.SbbContext context) {
            this.context = context;
            contextLatch.countDown();
        }

        private boolean awaitContext() throws InterruptedException {
            return contextLatch.await(5, TimeUnit.SECONDS);
        }
    }
}
