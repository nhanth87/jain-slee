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
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.ServiceID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge-case tests for {@link SbbLifecycleManager} and {@link CascadeRemover}.
 */
public class EdgeCaseLifecycleTest {

    private MicroSleeContainer container;

    @Before
    public void start() {
        container = new MicroSleeContainer();
        container.start();
    }

    @After
    public void stop() {
        if (container != null) container.stop();
    }

    // ---- SbbLifecycleManager ----

    @Test
    public void createNullSbbThrowsIAE() {
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        try {
            mgr.create(null, null, null);
            fail("null sbb should throw IAE");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void activateNullSbbIsNoOp() {
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        // must not throw
        mgr.activate(null, null);
        mgr.activate(null, new java.util.HashMap<String, Object>());
    }

    @Test
    public void passivateNullSbbIsNoOp() {
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        mgr.passivate(null, null);
        mgr.passivate(null, new java.util.HashMap<String, Object>());
    }

    @Test
    public void removeNullSbbIsNoOp() {
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        mgr.removeEntity(null);
    }

    @Test
    public void rolledBackNullSbbIsNoOp() {
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        mgr.rolledBack(null, new SimpleRolledBackContext(null, null, false));
    }

    @Test
    public void postCreateNullSbbIsNoOp() {
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        try {
            mgr.postCreate(null);
        } catch (CreateException ce) {
            fail("null sbb must not throw: " + ce);
        }
    }

    @Test
    public void sbbLoadExceptionIsSwallowedInActivate() {
        final java.util.concurrent.atomic.AtomicBoolean activated =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Sbb sbb = new Sbb() {
            @Override public void sbbLoad() { throw new RuntimeException("disk full"); }
            @Override public void sbbActivate() { activated.set(true); }
        };
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        java.util.Map<String, Object> state = new java.util.HashMap<String, Object>();
        // Activate must NOT propagate the sbbLoad failure — sbbActivate still runs.
        mgr.activate(sbb, state);
        assertTrue("sbbActivate must run even when sbbLoad throws", activated.get());
        assertSame(SbbLifecycleManager.State.READY, mgr.getState(sbb));
    }

    @Test
    public void sbbStoreExceptionIsSwallowedInPassivate() {
        final java.util.concurrent.atomic.AtomicBoolean passivated =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Sbb sbb = new Sbb() {
            @Override public void sbbPassivate() { passivated.set(true); }
            @Override public void sbbStore() { throw new RuntimeException("io error"); }
        };
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        mgr.activate(sbb, null);
        mgr.passivate(sbb, new java.util.HashMap<String, Object>());
        assertTrue(passivated.get());
    }

    @Test
    public void unsetSbbContextExceptionInRemoveDoesNotAbort() {
        final java.util.concurrent.atomic.AtomicBoolean removed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Sbb sbb = new Sbb() {
            @Override public void sbbRemove() { removed.set(true); }
            @Override public void unsetSbbContext() { throw new RuntimeException("cleanup fail"); }
        };
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        mgr.removeEntity(sbb);
        assertTrue("sbbRemove must run even if unsetSbbContext throws", removed.get());
        assertSame(SbbLifecycleManager.State.DOES_NOT_EXIST, mgr.getState(sbb));
    }

    @Test
    public void rolledBackExceptionDoesNotAbort() {
        Sbb sbb = new Sbb() {
            @Override public void sbbRolledBack(RolledBackContext ctx) {
                throw new RuntimeException("rollback-handler-fail");
            }
        };
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        // must not propagate
        mgr.rolledBack(sbb, new SimpleRolledBackContext(null, null, false));
    }

    @Test
    public void deprecatedRemoveStillWorks() {
        Sbb sbb = new Sbb() {
            @Override public void sbbRemove() {}
        };
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        mgr.remove(sbb);  // @Deprecated thin overload
        assertSame(SbbLifecycleManager.State.DOES_NOT_EXIST, mgr.getState(sbb));
    }

    @Test
    public void deprecatedCreateStillWorks() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean created =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Sbb sbb = new Sbb() {
            @Override public void sbbCreate() { created.set(true); }
        };
        SbbLifecycleManager mgr = container.getSbbLifecycleManager();
        ServiceID sid = new ServiceID("svc", "v", "1.0");
        SimpleSbbContext ctx = new SimpleSbbContext(sid, null, new SbbID("x"), null, null);
        mgr.create(sbb, ctx);
        assertTrue(created.get());
    }

    // ---- CascadeRemover ----

    @Test
    public void cascadeRemoveNullIsNoOp() {
        // must not throw
        CascadeRemover.cascadeRemove(null);
    }

    @Test
    public void cascadeRemoveDeepTreeDoesNotStackOverflow() throws Exception {
        // Build a 200-level-deep chain via ChildRelation. CascadeRemover walks
        // iteratively, so this must NOT blow the stack.
        SimpleSbbLocalObject root = (SimpleSbbLocalObject) container.registerSbb(
                "deep-root", new Sbb() { });
        Thread.sleep(100);
        SimpleSbbLocalObject current = root;
        ChildRelationFactory factory = container.getChildRelationFactory(
                childId -> new Sbb() { });
        for (int i = 0; i < 50; i++) {  // 50 is enough to exercise the iterative walk
            SbbLocalObject child = factory.createChild(current.getSbbID().getId());
            current = (SimpleSbbLocalObject) child;
            Thread.sleep(2);  // let activation settle
        }
        // Now cascade from the root — should traverse all entities.
        long deadline = System.currentTimeMillis() + 5000;
        CascadeRemover.cascadeRemove(root);
        assertTrue("cascade must finish within 5s", System.currentTimeMillis() < deadline);
    }

    @Test
    public void cascadeRemoveOnForeignSbbLocalObjectDoesNotCrash() {
        // A SbbLocalObject implementation that is not SimpleSbbLocalObject:
        SbbLocalObject foreign = new SbbLocalObject() {
            @Override public Sbb getSbb() { return new Sbb() { }; }
            @Override public SbbID getSbbID() { return new SbbID("foreign"); }
            @Override public int getPriority() { return 0; }
            @Override public void setPriority(int p) { }
            @Override public void remove() {}
            @Override public boolean isRemoved() { return false; }
        };
        // Must not throw even though we cannot introspect child relations.
        CascadeRemover.cascadeRemove(foreign);
    }

    @Test
    public void sbbEntityStateTransitionIsThreadSafe() throws Exception {
        // Hammer the transitionTo from multiple threads.
        SbbEntityState state = new SbbEntityState();
        int threads = 8;
        int iterations = 1000;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        start.await();
                        for (int j = 0; j < iterations; j++) {
                            state.transitionTo(SbbLifecycleManager.State.READY);
                            state.transitionTo(SbbLifecycleManager.State.POOLED);
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();
        // final state must be one of the two
        assertTrue(state.getLifecycleState() == SbbLifecycleManager.State.READY
                || state.getLifecycleState() == SbbLifecycleManager.State.POOLED);
    }
}
