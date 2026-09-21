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
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.core.recovery.RecoverySnapshot;
import com.microjainslee.core.recovery.SessionRecoveryService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Production P3 / Sprint S7 — wiring tests for the dead-slot rehydration
 * seam: snapshot capture at removal time, reconstruction on
 * {@code tryRehydrateAndDeliver}, and the no-snapshot inline fallback the
 * router uses when the entity died before any snapshot existed.
 *
 * @author Tran Nhan (nhanth87)
 */
public class SbbRehydrationWiringTest {

    private static final long DEADLINE_MS = 5_000L;

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(16)
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
    public void snapshotCapturedOnRemovalAndRehydrateDeliversEvent() throws Exception {
        container.registerSbbType(CountingSbb.class, CountingSbb::new);
        SimpleSbbLocalObject old = container.acquireEntity("e1", CountingSbb.class);
        assertTrue(old.awaitReady(DEADLINE_MS, TimeUnit.MILLISECONDS));
        old.getEntityState().getCmpFields().put("state", "step1");
        InMemoryActivityContext aci = container.createActivityContext("ac1");
        container.attach("ac1", old);
        CountingSbb oldSbb = (CountingSbb) old.getSbb();

        // Remove through the public path — the removal listener must
        // capture a RecoverySnapshot BEFORE detaching / clearing CMP.
        container.releaseEntity("e1");
        SessionRecoveryService svc = container.getSessionRecoveryService();
        assertNotNull(svc);
        assertTrue("snapshot must be captured at removal time",
                awaitTrue(() -> svc.getSnapshot("e1").isPresent()));
        RecoverySnapshot snap = svc.getSnapshot("e1").orElseThrow();
        assertEquals("e1", snap.entityId());
        assertEquals(CountingSbb.class.getName(), snap.sbbClassFqn());
        assertEquals("step1", snap.cmpFields().get("state"));
        assertTrue(snap.attachedAciNames().contains("ac1"));
        assertNull("entity must be gone after removal", container.getSbbLocalObject("e1"));

        // Rehydrate: the exact call the router makes when a still-in-flight
        // event finds the slot empty. The snapshot is consumed atomically.
        boolean delivered = svc.tryRehydrateAndDeliver("e1", new TestEvent(), aci, oldSbb);
        assertTrue("rehydration must succeed and re-dispatch", delivered);

        SimpleSbbLocalObject fresh = container.getSbbLocalObject("e1");
        assertNotNull("entity must be re-registered by rehydration", fresh);
        assertNotSame(old, fresh);
        assertTrue(fresh.awaitReady(DEADLINE_MS, TimeUnit.MILLISECONDS));
        assertEquals("CMP state must be restored on the fresh entity",
                "step1", fresh.getEntityState().getCmpFields().get("state"));
        assertTrue("fresh entity must be re-attached to its ACI",
                aci.getAttachedSbbs().contains(fresh));
        assertEquals("the pending event must be replayed to the handler",
                1, oldSbb.deliveries.get());
    }

    @Test
    public void routerFallsBackToInlineDeliveryWhenEntityHasNoSnapshot() throws Exception {
        // Simulate Gap-SR-1 the hard way: the pool slot dies (force
        // termination) while the local object is still attached, and no
        // removal ever ran so there is no snapshot. The router must fall
        // back to delivering the event inline to the existing handler.
        container.registerSbbType(CountingSbb.class, CountingSbb::new);
        SimpleSbbLocalObject obj = container.acquireEntity("e2", CountingSbb.class);
        assertTrue(obj.awaitReady(DEADLINE_MS, TimeUnit.MILLISECONDS));
        InMemoryActivityContext aci = container.createActivityContext("ac2");
        container.attach("ac2", obj);
        CountingSbb sbb = (CountingSbb) obj.getSbb();

        assertTrue(container.getSbbEntityPool().forceTerminate("e2"));
        assertFalse("entity slot must be gone from the pool",
                container.getSbbEntityPool().forceTerminate("e2"));

        container.routeEvent(new TestEvent(), aci);

        assertTrue("no-snapshot MISSING_ENTITY must deliver inline",
                awaitTrue(() -> sbb.deliveries.get() >= 1));
        assertEquals("no rehydration must be counted without a snapshot",
                0, ((SessionRecoveryService) container.getSessionRecoveryService())
                        .activeSnapshotCount());
    }

    @Test
    public void reconstructionRefusedForUnregisteredType() {
        RecoverySnapshot bogus = new RecoverySnapshot(
                "e3", "no.such.SbbType", Collections.emptyMap(),
                Collections.emptySet(), System.currentTimeMillis(), null);
        assertFalse(container.reconstructFromSnapshot(bogus));
        assertNull(container.getSbbLocalObject("e3"));
    }

    // ───────────────────────────────────────────────────────────────────
    // Helpers / fixtures
    // ───────────────────────────────────────────────────────────────────

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private static final class TestEvent implements SleeEvent {
    }

    /** Records deliveries; never throws. */
    static final class CountingSbb implements Sbb, SleeEventHandler {
        final AtomicInteger deliveries = new AtomicInteger();

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            deliveries.incrementAndGet();
        }
    }
}
