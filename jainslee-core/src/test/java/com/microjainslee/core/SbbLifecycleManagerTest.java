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

import com.microjainslee.api.CreateException;
import com.microjainslee.api.RolledBackContext;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbContext;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the rewritten {@link SbbLifecycleManager} state machine.
 */
public class SbbLifecycleManagerTest {

    static class RecordingSbb implements Sbb {
        final AtomicInteger setCtxCount = new AtomicInteger(0);
        final AtomicInteger createCount = new AtomicInteger(0);
        final AtomicInteger postCreateCount = new AtomicInteger(0);
        final AtomicInteger activateCount = new AtomicInteger(0);
        final AtomicInteger passivateCount = new AtomicInteger(0);
        final AtomicInteger loadCount = new AtomicInteger(0);
        final AtomicInteger storeCount = new AtomicInteger(0);
        final AtomicInteger removeCount = new AtomicInteger(0);
        final AtomicInteger unsetCtxCount = new AtomicInteger(0);
        final AtomicInteger rolledBackCount = new AtomicInteger(0);

        @Override public void setSbbContext(SbbContext context) { setCtxCount.incrementAndGet(); }
        @Override public void sbbCreate() throws CreateException { createCount.incrementAndGet(); }
        @Override public void sbbPostCreate() throws CreateException { postCreateCount.incrementAndGet(); }
        @Override public void sbbActivate() { activateCount.incrementAndGet(); }
        @Override public void sbbPassivate() { passivateCount.incrementAndGet(); }
        @Override public void sbbLoad() { loadCount.incrementAndGet(); }
        @Override public void sbbStore() { storeCount.incrementAndGet(); }
        @Override public void sbbRemove() { removeCount.incrementAndGet(); }
        @Override public void unsetSbbContext() { unsetCtxCount.incrementAndGet(); }
        @Override public void sbbRolledBack(RolledBackContext context) { rolledBackCount.incrementAndGet(); }
    }

    private static SbbContext noopContext() {
        com.microjainslee.api.ServiceID sid =
                new com.microjainslee.api.ServiceID("test-svc", "com.microjainslee", "1.0");
        return new SimpleSbbContext(sid, null, null, null);
    }

    private static RolledBackContext stubContext() {
        return new SimpleRolledBackContext(null, null, false);
    }

    @Test
    public void createInvokesSetSbbContextAndSbbCreate() throws Exception {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        mgr.create(sbb, noopContext(), null);
        assertEquals(1, sbb.setCtxCount.get());
        assertEquals(1, sbb.createCount.get());
        assertSame(SbbLifecycleManager.State.POOLED, mgr.getState(sbb));
    }

    @Test
    public void postCreateInvokesCallback() throws Exception {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        mgr.postCreate(sbb);
        assertEquals(1, sbb.postCreateCount.get());
    }

    @Test
    public void activateInvokesSbbLoadAndSbbActivate() {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("counter", Integer.valueOf(5));
        mgr.activate(sbb, state);
        assertEquals(1, sbb.loadCount.get());
        assertEquals(1, sbb.activateCount.get());
        assertSame(SbbLifecycleManager.State.READY, mgr.getState(sbb));
    }

    @Test
    public void activateWithoutCmpStateSkipsSbbLoad() {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        mgr.activate(sbb, null);
        assertEquals(0, sbb.loadCount.get());
        assertEquals(1, sbb.activateCount.get());
        assertSame(SbbLifecycleManager.State.READY, mgr.getState(sbb));
    }

    @Test
    public void passivateInvokesSbbPassivateAndWritesState() {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        mgr.activate(sbb, null);
        Map<String, Object> out = new HashMap<String, Object>();
        mgr.passivate(sbb, out);
        assertEquals(1, sbb.passivateCount.get());
        assertEquals(1, sbb.storeCount.get());
        assertSame(SbbLifecycleManager.State.POOLED, mgr.getState(sbb));
    }

    @Test
    public void removeInvokesSbbRemoveAndUnsetSbbContext() {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        mgr.removeEntity(sbb);
        assertEquals(1, sbb.removeCount.get());
        assertEquals(1, sbb.unsetCtxCount.get());
        assertSame(SbbLifecycleManager.State.DOES_NOT_EXIST, mgr.getState(sbb));
    }

    @Test
    public void stateTransitionsPooledToReadyToPooled() throws Exception {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        mgr.create(sbb, noopContext(), null);
        assertSame(SbbLifecycleManager.State.POOLED, mgr.getState(sbb));
        mgr.activate(sbb, null);
        assertSame(SbbLifecycleManager.State.READY, mgr.getState(sbb));
        mgr.passivate(sbb, null);
        assertSame(SbbLifecycleManager.State.POOLED, mgr.getState(sbb));
        mgr.activate(sbb, null);
        assertSame(SbbLifecycleManager.State.READY, mgr.getState(sbb));
        mgr.removeEntity(sbb);
        assertSame(SbbLifecycleManager.State.DOES_NOT_EXIST, mgr.getState(sbb));
    }

    @Test
    public void rolledBackInvokesSbbRolledBack() {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        mgr.rolledBack(sbb, stubContext());
        assertEquals(1, sbb.rolledBackCount.get());
    }

    @Test
    public void sbbCreateExceptionPropagates() {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        final Sbb sbb = new Sbb() {
            @Override
            public void sbbCreate() throws CreateException {
                throw new CreateException("boom");
            }
        };
        try {
            mgr.create(sbb, noopContext(), null);
            fail("Expected CreateException");
        } catch (CreateException expected) {
            assertTrue(expected.getMessage().contains("boom"));
        }
    }

    @Test
    public void backwardCompatibleThinCreateStillWorks() {
        SbbLifecycleManager mgr = new SbbLifecycleManager();
        RecordingSbb sbb = new RecordingSbb();
        mgr.create(sbb, noopContext());
        assertEquals(1, sbb.setCtxCount.get());
        assertEquals(1, sbb.createCount.get());
    }
}
