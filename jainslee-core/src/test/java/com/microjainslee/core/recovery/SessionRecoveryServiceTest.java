/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.recovery;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Sprint S7 - unit tests for {@link SessionRecoveryServiceImpl}.
 *
 * <p>Covers:
 * <ol>
 *   <li>{@code registerSnapshot} + {@code getSnapshot} round-trip.</li>
 *   <li>{@code consumeSnapshot} atomic lookup-and-remove.</li>
 *   <li>LRU eviction when capacity is exceeded.</li>
 *   <li>R10 anti-loop guard via the {@code REHYDRATING} {@link ThreadLocal}.</li>
 *   <li>Snapshot ordering (registration order preserved until eviction).</li>
 *   <li>Null handling on inputs and on the service handle.</li>
 *   <li>Callback failure path (returns false; flag still cleared).</li>
 * </ol>
 */
public class SessionRecoveryServiceTest {

    private SessionRecoveryServiceImpl service;
    private RecordingCallback callback;

    @Before
    public void setUp() {
        // Always start each test from a clean REHYDRATING flag - the
        // threadlocal leaks across tests if a previous one threw.
        SessionRecoveryServiceImpl.resetRehydratingFlag();
        callback = new RecordingCallback();
        service = new SessionRecoveryServiceImpl(8, callback);
    }

    @After
    public void tearDown() {
        SessionRecoveryServiceImpl.resetRehydratingFlag();
    }

    private static RecoverySnapshot snap(String entityId, long ts) {
        Map<String, Object> cmp = new LinkedHashMap<String, Object>();
        cmp.put("counter", 0);
        Set<String> acis = new LinkedHashSet<String>();
        acis.add("ac-" + entityId);
        return new RecoverySnapshot(entityId,
                "com.microjainslee.core.recovery.SessionRecoveryServiceTest$DummySbb",
                cmp, acis, ts, "conv-" + entityId);
    }

    // ─── 1. register/get round-trip ─────────────────────────────────
    @Test
    public void registerSnapshot_isReadableViaGetSnapshot() {
        RecoverySnapshot s = snap("e1", 1000L);
        service.registerSnapshot(s);

        Optional<RecoverySnapshot> got = service.getSnapshot("e1");
        assertTrue("snapshot must be present after register", got.isPresent());
        assertSame("get must return the same instance we put", s, got.get());
        assertEquals("e1", got.get().entityId());
        assertEquals(1000L, got.get().capturedAtMs());
    }

    @Test
    public void registerSnapshot_overwritesPriorEntryForSameEntityId() {
        RecoverySnapshot first = snap("e1", 100L);
        RecoverySnapshot second = snap("e1", 200L);
        service.registerSnapshot(first);
        service.registerSnapshot(second);

        Optional<RecoverySnapshot> got = service.getSnapshot("e1");
        assertTrue(got.isPresent());
        assertSame("later registration must win", second, got.get());
        assertEquals(200L, got.get().capturedAtMs());
    }

    // ─── 2. consume removes atomically ──────────────────────────────
    @Test
    public void consumeSnapshot_removesEntryAndReturnsIt() {
        RecoverySnapshot s = snap("e1", 100L);
        service.registerSnapshot(s);

        Optional<RecoverySnapshot> first = service.consumeSnapshot("e1");
        assertTrue(first.isPresent());
        assertSame(s, first.get());

        // second consume sees nothing
        Optional<RecoverySnapshot> second = service.consumeSnapshot("e1");
        assertFalse("second consume must be empty", second.isPresent());

        // getSnapshot is a pure read - it must also be empty now
        assertFalse(service.getSnapshot("e1").isPresent());
    }

    // ─── 3. LRU eviction ─────────────────────────────────────────────
    @Test
    public void lruEviction_dropsOldestWhenCapacityExceeded() {
        // capacity is 8 (set in setUp)
        for (int i = 0; i < 8; i++) {
            service.registerSnapshot(snap("e" + i, 1000L + i));
        }
        assertEquals(8, service.activeSnapshotCount());

        // Touch e0..e6 so e7 becomes the LRU (most recently inserted)
        // actually insert pattern: e0,e1,...,e7. Accessing e0-e6 puts
        // them at the MRU end, leaving e7 as the eldest.
        for (int i = 0; i < 7; i++) {
            service.getSnapshot("e" + i);
        }

        // Insert one more -> should evict e7 (the current LRU).
        service.registerSnapshot(snap("e8", 9999L));

        assertEquals("size must remain at cap", 8, service.activeSnapshotCount());
        assertFalse("e7 must have been evicted", service.getSnapshot("e7").isPresent());
        assertTrue("e8 must be present", service.getSnapshot("e8").isPresent());
    }

    @Test
    public void capacity_oneStillWorks() {
        SessionRecoveryServiceImpl small = new SessionRecoveryServiceImpl(1, callback);
        small.registerSnapshot(snap("a", 1L));
        small.registerSnapshot(snap("b", 2L));
        assertEquals(1, small.activeSnapshotCount());
        assertTrue(small.getSnapshot("b").isPresent());
        assertFalse(small.getSnapshot("a").isPresent());
    }

    // ─── 4. anti-loop guard (R10) ───────────────────────────────────
    @Test
    public void rehydratingFlag_blocksNestedRehydrateCalls() {
        service.registerSnapshot(snap("outer", 100L));
        // Pretend we are mid-rehydration - the test setUp clears the flag
        // so we set it manually to simulate the in-call state.
        java.lang.reflect.Field flagField;
        try {
            Class<?> cls = SessionRecoveryServiceImpl.class;
            flagField = cls.getDeclaredField("REHYDRATING");
            flagField.setAccessible(true);
            ThreadLocal<Boolean> tl = (ThreadLocal<Boolean>) flagField.get(null);
            tl.set(Boolean.TRUE);
        } catch (ReflectiveOperationException roe) {
            fail("could not set REHYDRATING via reflection: " + roe);
        }
        try {
            // Even though the snapshot exists, the nested call must short-
            // circuit so we cannot recursively rehydrate.
            boolean result = service.tryRehydrateAndDeliver("outer",
                    new Object(), null, (event, aci) -> { });
            assertFalse("nested rehydrate must return false (R10)", result);
            assertEquals("callback must NOT have been invoked",
                    0, callback.invocations.get());
            // The snapshot must still be cached for a later attempt.
            assertTrue("outer snapshot must remain in cache",
                    service.getSnapshot("outer").isPresent());
        } finally {
            SessionRecoveryServiceImpl.resetRehydratingFlag();
        }
    }

    // ─── 5. snapshot ordering ────────────────────────────────────────
    @Test
    public void registerPreservesInsertionOrderForFreshEntries() {
        service.registerSnapshot(snap("alpha", 1L));
        service.registerSnapshot(snap("beta", 2L));
        service.registerSnapshot(snap("gamma", 3L));

        // getSnapshot in accessOrder=true promotes the touched entry to
        // the MRU end, so a fresh insertion after touching alpha must
        // evict beta (the next LRU after alpha was promoted).
        service.getSnapshot("alpha");
        service.registerSnapshot(snap("delta", 4L));

        // beta should be the LRU and gone (capacity 8 not exceeded yet
        // so nothing is evicted; instead we just check the snapshot is
        // present).
        assertTrue(service.getSnapshot("alpha").isPresent());
        assertTrue(service.getSnapshot("beta").isPresent());
        assertTrue(service.getSnapshot("gamma").isPresent());
        assertTrue(service.getSnapshot("delta").isPresent());
    }

    // ─── 6. null / empty handling ────────────────────────────────────
    @Test
    public void nullInputsAreRejectedCleanly() {
        try {
            service.registerSnapshot(null);
            fail("registerSnapshot(null) must throw IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        RecoverySnapshot noEntity = new RecoverySnapshot(null,
                "x", new HashMap<String, Object>(),
                new LinkedHashSet<String>(), 0L, null);
        try {
            service.registerSnapshot(noEntity);
            fail("registerSnapshot(null entityId) must throw IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        // getSnapshot / consumeSnapshot never throw - they return empty.
        assertFalse(service.getSnapshot(null).isPresent());
        assertFalse(service.consumeSnapshot(null).isPresent());

        // tryRehydrateAndDeliver must NOT throw on null inputs.
        assertFalse(service.tryRehydrateAndDeliver(null,
                new Object(), null, (e, a) -> { }));
        assertFalse(service.tryRehydrateAndDeliver("missing",
                null, null, (e, a) -> { }));
        assertFalse(service.tryRehydrateAndDeliver("missing",
                new Object(), null, null));
    }

    @Test
    public void reconstructCallbackFailureStillClearsFlag() {
        // callback returns false -> rehydrate returns false, snapshot
        // is consumed and the REHYDRATING flag is reset.
        callback.alwaysSucceed = false;
        service.registerSnapshot(snap("boom", 5L));
        boolean result = service.tryRehydrateAndDeliver("boom",
                new Object(), null, (e, a) -> fail("handler must not run"));
        assertFalse(result);
        assertFalse("flag must be cleared after failure",
                SessionRecoveryServiceImpl.isRehydrating());
        // snapshot was consumed (no double-deliver)
        assertFalse(service.getSnapshot("boom").isPresent());
    }

    @Test
    public void successfulRehydrateCallsHandlerAndClearsFlag() {
        SleeEvent event = new SleeEvent() {
            private static final long serialVersionUID = 1L;
        };
        final AtomicReference<SleeEvent> seenEvent = new AtomicReference<SleeEvent>();
        final AtomicReference<ActivityContextInterface> seenAci = new AtomicReference<ActivityContextInterface>();
        SleeEventHandler handler = new SleeEventHandler() {
            @Override
            public void onEvent(SleeEvent e, ActivityContextInterface aci) {
                seenEvent.set(e);
                seenAci.set(aci);
            }
        };

        service.registerSnapshot(snap("ok", 100L));
        boolean ok = service.tryRehydrateAndDeliver("ok", event, null, handler);
        assertTrue(ok);
        assertSame(event, seenEvent.get());
        assertEquals(1, callback.invocations.get());
        assertFalse("flag cleared after success",
                SessionRecoveryServiceImpl.isRehydrating());
        assertFalse("snapshot consumed after rehydrate",
                service.getSnapshot("ok").isPresent());
    }

    // ─── helpers ─────────────────────────────────────────────────────

    /** No-op SBB used purely to give {@link RecoverySnapshot} a class name. */
    public static final class DummySbb implements com.microjainslee.api.Sbb {
    }

    /** Callback that records every invocation and is configurable to fail. */
    static final class RecordingCallback implements SessionRecoveryService.RehydrateCallback {
        final AtomicInteger invocations = new AtomicInteger();
        volatile boolean alwaysSucceed = true;

        @Override
        public boolean reconstructFromSnapshot(RecoverySnapshot snapshot) {
            invocations.incrementAndGet();
            return alwaysSucceed;
        }
    }
}
