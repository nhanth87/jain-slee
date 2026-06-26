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

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SbbTransactionContextTest {

    private EventRouter eventRouter;
    private SleeTimerSchedulerBridge timerBridge;

    @Before
    public void setUp() {
        eventRouter = new EventRouter(8, false);
        timerBridge = SleeTimerSchedulerBridge.create(eventRouter);
    }

    @After
    public void tearDown() {
        timerBridge.shutdown();
        eventRouter.shutdown();
    }

    @Test
    public void rollbackUndoesAttachAndTimerBinding() {
        InMemoryActivityContext aci = new InMemoryActivityContext("tx-ac");
        SbbTransactionContext tx = new SbbTransactionContext(aci, timerBridge);
        FakeLocalObject sbb = new FakeLocalObject("rollback-sbb");

        tx.begin();
        tx.recordAttach(sbb);
        tx.recordTimerBind(sbb);
        assertTrue(aci.getAttachedSbbs().contains(sbb));

        tx.rollback();

        assertFalse(aci.getAttachedSbbs().contains(sbb));
        assertFalse(tx.isActive());
    }

    @Test
    public void commitKeepsAttachChanges() {
        InMemoryActivityContext aci = new InMemoryActivityContext("commit-ac");
        SbbTransactionContext tx = new SbbTransactionContext(aci, timerBridge);
        FakeLocalObject sbb = new FakeLocalObject("commit-sbb");

        tx.begin();
        tx.recordAttach(sbb);
        tx.commit();

        assertTrue(aci.getAttachedSbbs().contains(sbb));
        assertFalse(tx.isActive());
    }

    @Test
    public void activityContextAttachUsesCurrentTransaction() {
        InMemoryActivityContext aci = new InMemoryActivityContext("registry-ac");
        FakeLocalObject sbb = new FakeLocalObject("registry-sbb");
        SbbTransactionContext tx = ActivityContextTransactionRegistry.begin(aci, timerBridge);
        try {
            aci.attach(sbb);
            assertTrue(aci.getAttachedSbbs().contains(sbb));
            tx.rollback();
        } finally {
            ActivityContextTransactionRegistry.clear(tx);
        }
        assertFalse(aci.getAttachedSbbs().contains(sbb));
    }

    @Test
    public void defaultErrorHandlingPolicyRollsBackActiveTransaction() {
        InMemoryActivityContext aci = new InMemoryActivityContext("policy-ac");
        SbbTransactionContext tx = new SbbTransactionContext(aci, timerBridge);
        FakeLocalObject sbb = new FakeLocalObject("policy-sbb");
        tx.begin();
        tx.recordAttach(sbb);

        tx.rollback();

        assertFalse(aci.getAttachedSbbs().contains(sbb));
    }

    @Test
    public void sbbLocalInvokerReturnsValueFromEntityThread() {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(0, 1, false);
        FakeLocalObject localObject = new FakeLocalObject("invoke-sbb");
        pool.acquire("invoke-sbb", new java.util.function.Supplier<Sbb>() {
            @Override
            public Sbb get() {
                return new Sbb() { };
            }
        });

        String value = SbbLocalInvoker.invoke(pool, localObject, new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                return "ok";
            }
        });
        assertEquals("ok", value);
        pool.shutdown();
    }

    @Test
    public void sbbLocalInvokerRejectsRemovedEntity() {
        FakeLocalObject localObject = new FakeLocalObject("removed");
        localObject.markRemoved();
        boolean failed = false;
        try {
            SbbLocalInvoker.invoke(null, localObject, new Runnable() {
                @Override
                public void run() {
                }
            });
        } catch (IllegalStateException e) {
            failed = true;
        }
        assertTrue(failed);
    }

    private static final class FakeLocalObject implements SbbLocalObject {
        private final SbbID id;
        private boolean removed;
        private int priority;

        private FakeLocalObject(String id) {
            this.id = new SbbID(id);
        }

        private void markRemoved() {
            removed = true;
        }

        @Override
        public Sbb getSbb() {
            return new Sbb() { };
        }

        @Override
        public SbbID getSbbID() {
            return id;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public void setPriority(int priority) {
            this.priority = priority;
        }

        @Override
        public void remove() {
            removed = true;
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }
    }
}
