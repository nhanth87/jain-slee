/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.logging;

import org.apache.logging.log4j.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P1.3 — Unit tests for {@link EventMdc}.
 *
 * <p>Each test starts from a clean ThreadContext (the {@code @Before} hook
 * calls {@link EventMdc#clear()}) and asserts the observable MDC state after
 * a specific call sequence. These tests do not touch the EventRouter — the
 * router's MDC usage is covered by the higher-level routing tests that
 * already exist in {@code com.microjainslee.core}.
 */
public class EventMdcTest {

    @Before
    public void resetContext() {
        EventMdc.clear();
    }

    @After
    public void cleanupContext() {
        EventMdc.clear();
    }

    @Test
    public void startPopulatesKnownFields() {
        EventMdc.start("Sbb#1", "ACI-A", "MyEvent");

        assertEquals("Sbb#1", ThreadContext.get(EventMdc.KEY_SBB_ID));
        assertEquals("ACI-A", ThreadContext.get(EventMdc.KEY_ACI_NAME));
        assertEquals("MyEvent", ThreadContext.get(EventMdc.KEY_EVENT_TYPE));
        // durationNs is not set by start() — finish() sets it.
        assertNull(ThreadContext.get(EventMdc.KEY_DURATION_NS));
        // txStatus is "PENDING" until finish() stamps the real value.
        assertEquals("PENDING", ThreadContext.get(EventMdc.KEY_TX_STATUS));
        // nodeId is always the literal "local" in P1 (placeholder for P2 cluster).
        assertEquals(EventMdc.NODE_ID_LOCAL, ThreadContext.get(EventMdc.KEY_NODE_ID));
        assertEquals("local", ThreadContext.get(EventMdc.KEY_NODE_ID));
    }

    @Test
    public void startCoalescesNullArgsToPlaceholder() {
        EventMdc.start(null, null, null);

        assertEquals("?", ThreadContext.get(EventMdc.KEY_SBB_ID));
        assertEquals("?", ThreadContext.get(EventMdc.KEY_ACI_NAME));
        assertEquals("?", ThreadContext.get(EventMdc.KEY_EVENT_TYPE));
    }

    @Test
    public void setSbbIdOverwritesPrevious() {
        EventMdc.start("Sbb#initial", "ACI-A", "MyEvent");
        EventMdc.setSbbId("Sbb#replaced");

        assertEquals("Sbb#replaced", ThreadContext.get(EventMdc.KEY_SBB_ID));
        // Other fields must remain intact.
        assertEquals("ACI-A", ThreadContext.get(EventMdc.KEY_ACI_NAME));
        assertEquals("MyEvent", ThreadContext.get(EventMdc.KEY_EVENT_TYPE));
    }

    @Test
    public void setSbbIdCoalescesNull() {
        EventMdc.start("Sbb#initial", "ACI-A", "MyEvent");
        EventMdc.setSbbId(null);

        assertEquals("?", ThreadContext.get(EventMdc.KEY_SBB_ID));
    }

    @Test
    public void finishStampsDurationAndTxStatus() {
        EventMdc.start("Sbb#1", "ACI-A", "MyEvent");
        long startNanos = System.nanoTime();
        // Spend a little wall time so the durationNs is non-zero.
        long accumulator = 0;
        for (int i = 0; i < 1000; i++) {
            accumulator += i;
        }
        assertTrue("warm-up loop ran", accumulator >= 0); // keep optimiser honest

        EventMdc.finish(startNanos, "COMMITTED");

        String duration = ThreadContext.get(EventMdc.KEY_DURATION_NS);
        assertNotNull("durationNs must be set by finish()", duration);
        long elapsedNs = Long.parseLong(duration);
        assertTrue("durationNs must be >= 0", elapsedNs >= 0);
        assertEquals("COMMITTED", ThreadContext.get(EventMdc.KEY_TX_STATUS));
    }

    @Test
    public void finishOverwritesPendingTxStatus() {
        EventMdc.start("Sbb#1", "ACI-A", "MyEvent");
        assertEquals("PENDING", ThreadContext.get(EventMdc.KEY_TX_STATUS));

        EventMdc.finish(System.nanoTime(), "ROLLED_BACK");
        assertEquals("ROLLED_BACK", ThreadContext.get(EventMdc.KEY_TX_STATUS));
    }

    @Test
    public void finishCoalescesNullStatusToUnknown() {
        EventMdc.start("Sbb#1", "ACI-A", "MyEvent");
        EventMdc.finish(System.nanoTime(), null);
        assertEquals("UNKNOWN", ThreadContext.get(EventMdc.KEY_TX_STATUS));
    }

    @Test
    public void clearRemovesEveryField() {
        EventMdc.start("Sbb#1", "ACI-A", "MyEvent");
        EventMdc.finish(System.nanoTime(), "COMMITTED");

        EventMdc.clear();

        assertNull(ThreadContext.get(EventMdc.KEY_SBB_ID));
        assertNull(ThreadContext.get(EventMdc.KEY_ACI_NAME));
        assertNull(ThreadContext.get(EventMdc.KEY_EVENT_TYPE));
        assertNull(ThreadContext.get(EventMdc.KEY_DURATION_NS));
        assertNull(ThreadContext.get(EventMdc.KEY_TX_STATUS));
        assertNull(ThreadContext.get(EventMdc.KEY_NODE_ID));
    }

    @Test
    public void clearIsIdempotent() {
        // Clearing twice (or before any start) must not throw.
        EventMdc.clear();
        EventMdc.start("Sbb#1", "ACI-A", "MyEvent");
        EventMdc.clear();
        EventMdc.clear();
    }

    @Test
    public void fullLifecycleLooksLikeEventRouterPath() {
        // Mirror the exact call sequence EventRouter.dispatchWithTransaction
        // performs today so a regression in the MDC wiring would surface
        // here even if the higher-level routing tests pass.
        long startNanos = System.nanoTime();
        EventMdc.start("?", "ACI-B", "LifecycleEvent");
        EventMdc.setSbbId("Sbb#attached");
        EventMdc.finish(startNanos, "COMMITTED");
        EventMdc.clear();

        assertNull(ThreadContext.get(EventMdc.KEY_SBB_ID));
        assertNull(ThreadContext.get(EventMdc.KEY_ACI_NAME));
        assertNull(ThreadContext.get(EventMdc.KEY_EVENT_TYPE));
        assertNull(ThreadContext.get(EventMdc.KEY_DURATION_NS));
        assertNull(ThreadContext.get(EventMdc.KEY_TX_STATUS));
        assertNull(ThreadContext.get(EventMdc.KEY_NODE_ID));
    }
}
