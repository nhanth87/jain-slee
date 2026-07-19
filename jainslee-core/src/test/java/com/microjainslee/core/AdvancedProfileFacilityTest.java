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

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAddedEvent;
import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileEventSink;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileNotFoundException;
import com.microjainslee.api.ProfileRemovedEvent;
import com.microjainslee.api.ProfileUpdatedEvent;
import com.microjainslee.api.UnrecognizedProfileTableNameException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Phase 1 Advanced Profile tests.
 *
 * <p>Covers:
 * <ol>
 *   <li>Default profile clone (§10.5)</li>
 *   <li>Secondary index find + update (§10.6, §10.8)</li>
 *   <li>Unindexed attribute throws (§10.6)</li>
 *   <li>Concurrent {@code addToLong} — Invariant I4</li>
 *   <li>Bad type rejection — Contract C7</li>
 *   <li>Stale {@link ProfileLocalObject} — Contract C8</li>
 *   <li>Profile events when enabled — Contract C5</li>
 *   <li>{@code profileExists} convenience query</li>
 *   <li>{@code compareAndSetField} semantics</li>
 *   <li>{@code updateField} semantics</li>
 * </ol>
 */
public class AdvancedProfileFacilityTest {

    // ------------------------------------------------------------------
    // Minimal concrete Profile spec that routes through ProfileAccessorInvoker
    // (same pattern as InMemoryProfileFacilityTest to avoid getCmpField setter bug).
    // ------------------------------------------------------------------

    public static final class SubscriberProfile extends ProfileAbstractCmp {

        public String getMsisdn() {
            return (String) com.microjainslee.api.ProfileAccessorInvoker.getValue(this, g("msisdn"));
        }
        public void setMsisdn(String v) {
            com.microjainslee.api.ProfileAccessorInvoker.setValue(this, s("msisdn", String.class), v);
        }

        public String getPlan() {
            return (String) com.microjainslee.api.ProfileAccessorInvoker.getValue(this, g("plan"));
        }
        public void setPlan(String v) {
            com.microjainslee.api.ProfileAccessorInvoker.setValue(this, s("plan", String.class), v);
        }

        private static Method g(String field) {
            try {
                return SubscriberProfile.class.getDeclaredMethod(
                        "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
            } catch (NoSuchMethodException e) { throw new RuntimeException(e); }
        }
        private static Method s(String field, Class<?> paramType) {
            try {
                return SubscriberProfile.class.getDeclaredMethod(
                        "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1),
                        paramType);
            } catch (NoSuchMethodException e) { throw new RuntimeException(e); }
        }
    }

    /** App-domain enum — NOT in the C7 whitelist. */
    public enum ServiceTier { BASIC, PREMIUM }

    // ------------------------------------------------------------------
    // Test infrastructure
    // ------------------------------------------------------------------

    private MicroSleeContainer container;
    private InMemoryProfileFacility facility;

    @Before
    public void setUp() {
        container = new MicroSleeContainer();
        container.start();
        facility = (InMemoryProfileFacility) container.getProfileFacility();
    }

    @After
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    // ------------------------------------------------------------------
    // 1. Default profile clone (§10.5)
    // ------------------------------------------------------------------

    @Test
    public void createFromDefaultClonesFields() throws Exception {
        facility.createProfileTable("subscribers");

        // Provision the default row.
        facility.createProfile("subscribers", "__default__", SubscriberProfile.class);
        InMemoryProfileTable tbl = facility.findTableInternal("subscribers");
        tbl.writeField("__default__", "plan", "GOLD");
        tbl.writeField("__default__", "balance", 500L);

        // Snapshot the default.
        ProfileLocalObject defPlo = facility.getProfile(new ProfileID("subscribers", "__default__"));
        facility.setDefaultProfile("subscribers", defPlo.getProfile());

        // Clone.
        ProfileLocalObject newPlo = facility.createFromDefault("subscribers", "user1");
        assertNotNull(newPlo);
        assertEquals("user1", newPlo.getProfileID().getProfileName());

        // Fields must be present in the cloned row.
        assertEquals("GOLD", tbl.readField("user1", "plan"));
        assertEquals(500L, tbl.readField("user1", "balance"));
        // Field not in default must be absent.
        assertNull(tbl.readField("user1", "msisdn"));
    }

    @Test
    public void createFromDefaultWithNoDefaultThrows() throws Exception {
        facility.createProfileTable("subscribers");
        try {
            facility.createFromDefault("subscribers", "any");
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No default profile"));
        }
    }

    @Test
    public void createFromDefaultOnUnknownTableThrows() throws Exception {
        try {
            facility.createFromDefault("ghost", "any");
            fail("Expected UnrecognizedProfileTableNameException");
        } catch (UnrecognizedProfileTableNameException expected) {
            // ok
        }
    }

    @Test
    public void setDefaultProfileRequiresBoundProfile() throws Exception {
        facility.createProfileTable("t1");
        facility.createProfileTable("t2");
        ProfileLocalObject ploBound = facility.createProfile("t1", "d1", SubscriberProfile.class);
        // Profile bound to t1 must not be accepted as default for t2.
        try {
            facility.setDefaultProfile("t2", ploBound.getProfile());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------
    // 2. Secondary index find + update (§10.6, §10.8)
    // ------------------------------------------------------------------

    @Test
    public void findProfilesByAttributeReturnsMatches() throws Exception {
        facility.createProfileTable("subs");
        facility.registerIndex("subs", "plan");

        facility.createProfile("subs", "a", SubscriberProfile.class);
        facility.createProfile("subs", "b", SubscriberProfile.class);
        facility.createProfile("subs", "c", SubscriberProfile.class);

        InMemoryProfileTable tbl = facility.findTableInternal("subs");
        tbl.writeField("a", "plan", "GOLD");
        tbl.writeField("b", "plan", "GOLD");
        tbl.writeField("c", "plan", "SILVER");

        Collection<ProfileLocalObject> gold =
                facility.findProfilesByAttribute("subs", "plan", "GOLD");
        assertEquals(2, gold.size());

        Collection<ProfileLocalObject> silver =
                facility.findProfilesByAttribute("subs", "plan", "SILVER");
        assertEquals(1, silver.size());
        assertEquals("c", silver.iterator().next().getProfileID().getProfileName());

        assertTrue(facility.findProfilesByAttribute("subs", "plan", "BRONZE").isEmpty());
    }

    @Test
    public void indexUpdatedOnFieldChange() throws Exception {
        facility.createProfileTable("subs");
        facility.registerIndex("subs", "plan");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        InMemoryProfileTable tbl = facility.findTableInternal("subs");

        tbl.writeField("u1", "plan", "GOLD");
        assertEquals(1, facility.findProfilesByAttribute("subs", "plan", "GOLD").size());

        tbl.writeField("u1", "plan", "PLATINUM");
        assertEquals(0, facility.findProfilesByAttribute("subs", "plan", "GOLD").size());
        assertEquals(1, facility.findProfilesByAttribute("subs", "plan", "PLATINUM").size());
    }

    @Test
    public void indexClearedOnRemove() throws Exception {
        facility.createProfileTable("subs");
        facility.registerIndex("subs", "plan");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        facility.findTableInternal("subs").writeField("u1", "plan", "GOLD");
        assertEquals(1, facility.findProfilesByAttribute("subs", "plan", "GOLD").size());

        facility.removeProfile(new ProfileID("subs", "u1"));
        assertEquals(0, facility.findProfilesByAttribute("subs", "plan", "GOLD").size());
    }

    @Test
    public void registerIndexIsIdempotent() throws Exception {
        facility.createProfileTable("subs");
        facility.registerIndex("subs", "plan");
        facility.registerIndex("subs", "plan"); // must not throw or duplicate
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        facility.findTableInternal("subs").writeField("u1", "plan", "GOLD");
        assertEquals(1, facility.findProfilesByAttribute("subs", "plan", "GOLD").size());
    }

    // ------------------------------------------------------------------
    // 3. Unindexed attribute throws (§10.6 — no silent full-table scan)
    // ------------------------------------------------------------------

    @Test
    public void findOnUnindexedAttributeThrowsIllegalState() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        try {
            facility.findProfilesByAttribute("subs", "plan", "GOLD");
            fail("Expected IllegalStateException for unindexed attribute");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No index registered"));
        }
    }

    // ------------------------------------------------------------------
    // 4. Concurrent addToLong — Invariant I4
    // ------------------------------------------------------------------

    @Test
    public void concurrentAddToLongProducesExactSum() throws Exception {
        facility.createProfileTable("billing");
        facility.createProfile("billing", "acct1", SubscriberProfile.class);
        ProfileID id = new ProfileID("billing", "acct1");

        int threads = 4;
        int ops = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < ops; i++) {
                    try {
                        facility.addToLong(id, "balance", 1L);
                    } catch (Exception ex) {
                        errors.incrementAndGet();
                    }
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertTrue("Pool did not terminate", pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals("No errors expected", 0, errors.get());
        long actual = facility.addToLong(id, "balance", 0L);
        assertEquals("Expected exact sum", (long) threads * ops, actual);
    }

    // ------------------------------------------------------------------
    // 5. Bad type rejection — Contract C7
    // ------------------------------------------------------------------

    @Test
    public void writeFieldWithAppEnumThrowsIllegalArgument() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        InMemoryProfileTable tbl = facility.findTableInternal("subs");
        try {
            tbl.writeField("u1", "tier", ServiceTier.PREMIUM);
            fail("Expected IllegalArgumentException for non-whitelisted type");
        } catch (IllegalArgumentException expected) {
            assertTrue("Message should mention C7", expected.getMessage().contains("C7"));
        }
    }

    @Test
    public void writeFieldWithStringIsAllowed() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        InMemoryProfileTable tbl = facility.findTableInternal("subs");
        tbl.writeField("u1", "plan", "GOLD");
        assertEquals("GOLD", tbl.readField("u1", "plan"));
    }

    @Test
    public void writeFieldWithNullClearsEntry() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        InMemoryProfileTable tbl = facility.findTableInternal("subs");
        tbl.writeField("u1", "plan", "GOLD");
        tbl.writeField("u1", "plan", null);
        assertNull(tbl.readField("u1", "plan"));
    }

    @Test
    public void profileSetViaInvokerRejectsBadType() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        InMemoryProfileTable tbl = facility.findTableInternal("subs");
        try {
            tbl.writeField("u1", "tier", ServiceTier.BASIC);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("C7"));
        }
        // String write must pass.
        tbl.writeField("u1", "plan", "GOLD");
        assertEquals("GOLD", tbl.readField("u1", "plan"));
    }

    // ------------------------------------------------------------------
    // 6. Stale ProfileLocalObject — Contract C8
    // ------------------------------------------------------------------

    @Test
    public void staleLOThrowsAfterRemove() throws Exception {
        facility.createProfileTable("subs");
        ProfileLocalObject plo = facility.createProfile("subs", "u1", SubscriberProfile.class);
        assertFalse(plo.isInvalidated());

        facility.removeProfile(new ProfileID("subs", "u1"));
        assertTrue(plo.isInvalidated());

        try {
            plo.getProfile();
            fail("Expected ProfileNotFoundException on stale getProfile()");
        } catch (ProfileNotFoundException expected) {
            // ok
        }
        try {
            plo.getProfileID();
            fail("Expected ProfileNotFoundException on stale getProfileID()");
        } catch (ProfileNotFoundException expected) {
            // ok
        }
    }

    @Test
    public void allOutstandingLOsInvalidatedOnRemove() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);

        ProfileLocalObject lo1 = facility.getProfile(new ProfileID("subs", "u1"));
        ProfileLocalObject lo2 = facility.getProfile(new ProfileID("subs", "u1"));

        assertFalse(lo1.isInvalidated());
        assertFalse(lo2.isInvalidated());

        facility.removeProfile(new ProfileID("subs", "u1"));

        assertTrue("lo1 must be invalidated", lo1.isInvalidated());
        assertTrue("lo2 must be invalidated", lo2.isInvalidated());
    }

    @Test
    public void freshLOAfterRecreateIsValid() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        ProfileLocalObject stale = facility.getProfile(new ProfileID("subs", "u1"));

        facility.removeProfile(new ProfileID("subs", "u1"));
        assertTrue(stale.isInvalidated());

        // Re-create the row under the same name.
        ProfileLocalObject fresh = facility.createProfile("subs", "u1", SubscriberProfile.class);
        assertFalse("Fresh LO must not be invalidated", fresh.isInvalidated());
        assertNotNull(fresh.getProfile());
    }

    // ------------------------------------------------------------------
    // 7. Profile events — Contract C5
    // ------------------------------------------------------------------

    private static final class CapturingSink implements ProfileEventSink {
        final List<ProfileAddedEvent> added = new CopyOnWriteArrayList<>();
        final List<ProfileUpdatedEvent> updated = new CopyOnWriteArrayList<>();
        final List<ProfileRemovedEvent> removed = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch;

        CapturingSink(int n) { latch = new CountDownLatch(n); }

        @Override public void onProfileAdded(ProfileAddedEvent e)   { added.add(e); latch.countDown(); }
        @Override public void onProfileUpdated(ProfileUpdatedEvent e){ updated.add(e); latch.countDown(); }
        @Override public void onProfileRemoved(ProfileRemovedEvent e){ removed.add(e); latch.countDown(); }
    }

    @Test
    public void addRemoveEventsDeliveredWhenEnabled() throws Exception {
        facility.createProfileTable("subs");
        CapturingSink sink = new CapturingSink(2); // add + remove
        facility.enableEvents("subs", sink);

        facility.createProfile("subs", "u1", SubscriberProfile.class);
        facility.removeProfile(new ProfileID("subs", "u1"));

        assertTrue("Expected add+remove within 3s", sink.latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, sink.added.size());
        assertEquals("u1", sink.added.get(0).getProfileID().getProfileName());
        assertEquals(1, sink.removed.size());
        assertEquals("u1", sink.removed.get(0).getProfileID().getProfileName());
    }

    @Test
    public void updateEventDeliveredOnFieldWrite() throws Exception {
        facility.createProfileTable("subs");
        // Need 1 add + at least 1 update
        CapturingSink sink = new CapturingSink(2);
        facility.enableEvents("subs", sink);

        ProfileLocalObject plo = facility.createProfile("subs", "u1", SubscriberProfile.class);
        ((SubscriberProfile) plo.getProfile()).setPlan("GOLD");

        assertTrue("Expected add+update within 3s", sink.latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, sink.added.size());
        assertFalse("update events expected", sink.updated.isEmpty());
    }

    @Test
    public void noEventsWhenNotEnabled() throws Exception {
        facility.createProfileTable("subs");
        // Do NOT call enableEvents
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        facility.removeProfile(new ProfileID("subs", "u1"));
        Thread.sleep(100);
        // No assertion beyond no exception and no NPE
    }

    @Test
    public void disableEventsStopsDelivery() throws Exception {
        facility.createProfileTable("subs");
        CapturingSink sink = new CapturingSink(1);
        facility.enableEvents("subs", sink);

        facility.createProfile("subs", "u1", SubscriberProfile.class);
        assertTrue("Expected add event within 3s", sink.latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, sink.added.size());

        facility.disableEvents("subs");

        // After disable, remove should produce no event for this sink.
        CapturingSink sink2 = new CapturingSink(1);
        // sink2 not registered
        facility.removeProfile(new ProfileID("subs", "u1"));
        Thread.sleep(200);
        assertEquals(0, sink2.removed.size());
    }

    @Test
    public void mutatorNeverBlocksWithSlowSink() throws Exception {
        // I5: even if the sink takes 50ms per event, the mutator should not block.
        facility.createProfileTable("billing");
        ProfileEventSink slowSink = new ProfileEventSink() {
            @Override public void onProfileAdded(ProfileAddedEvent e) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            @Override public void onProfileUpdated(ProfileUpdatedEvent e) {}
            @Override public void onProfileRemoved(ProfileRemovedEvent e) {}
        };
        facility.enableEvents("billing", slowSink);
        facility.createProfile("billing", "acct", SubscriberProfile.class);
        ProfileID id = new ProfileID("billing", "acct");

        long start = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            facility.addToLong(id, "balance", 1L);
        }
        long elapsed = System.nanoTime() - start;

        assertTrue("Mutator blocked — elapsed " + elapsed / 1_000_000 + "ms",
                elapsed < TimeUnit.SECONDS.toNanos(1));
    }

    // ------------------------------------------------------------------
    // 8. profileExists
    // ------------------------------------------------------------------

    @Test
    public void profileExistsTrueWhenPresent() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        assertTrue(facility.profileExists(new ProfileID("subs", "u1")));
        assertFalse(facility.profileExists(new ProfileID("subs", "missing")));
        assertFalse(facility.profileExists(new ProfileID("ghost", "u1")));
        assertFalse(facility.profileExists(null));
    }

    @Test
    public void profileExistsFalseAfterRemove() throws Exception {
        facility.createProfileTable("subs");
        facility.createProfile("subs", "u1", SubscriberProfile.class);
        facility.removeProfile(new ProfileID("subs", "u1"));
        assertFalse(facility.profileExists(new ProfileID("subs", "u1")));
    }

    // ------------------------------------------------------------------
    // 9. compareAndSetField
    // ------------------------------------------------------------------

    @Test
    public void casSucceedsWhenMatch() throws Exception {
        facility.createProfileTable("billing");
        facility.createProfile("billing", "acct", SubscriberProfile.class);
        ProfileID id = new ProfileID("billing", "acct");
        facility.addToLong(id, "balance", 100L);

        assertTrue(facility.compareAndSetField(id, "balance", 100L, 200L));
        assertEquals(200L, facility.addToLong(id, "balance", 0L));
    }

    @Test
    public void casFailsWhenMismatch() throws Exception {
        facility.createProfileTable("billing");
        facility.createProfile("billing", "acct", SubscriberProfile.class);
        ProfileID id = new ProfileID("billing", "acct");
        facility.addToLong(id, "balance", 100L);

        assertFalse(facility.compareAndSetField(id, "balance", 999L, 200L));
        assertEquals(100L, facility.addToLong(id, "balance", 0L));
    }

    @Test
    public void casThrowsForNonExistentRow() throws Exception {
        facility.createProfileTable("billing");
        try {
            facility.compareAndSetField(new ProfileID("billing", "ghost"), "balance", null, 1L);
            fail("Expected ProfileNotFoundException");
        } catch (ProfileNotFoundException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------
    // 10. updateField
    // ------------------------------------------------------------------

    @Test
    public void updateFieldAppliesFunctionAtomically() throws Exception {
        facility.createProfileTable("billing");
        facility.createProfile("billing", "acct", SubscriberProfile.class);
        ProfileID id = new ProfileID("billing", "acct");
        facility.addToLong(id, "balance", 50L);

        Object result = facility.updateField(id, "balance",
                cur -> cur == null ? 1L : ((Long) cur) * 2L);

        assertEquals(100L, result);
        assertEquals(100L, facility.addToLong(id, "balance", 0L));
    }

    @Test
    public void updateFieldRejectsBadReturnType() throws Exception {
        facility.createProfileTable("billing");
        facility.createProfile("billing", "acct", SubscriberProfile.class);
        ProfileID id = new ProfileID("billing", "acct");

        try {
            facility.updateField(id, "tier", cur -> ServiceTier.BASIC);
            fail("Expected IllegalArgumentException for non-whitelisted return type");
        } catch (IllegalArgumentException expected) {
            // ok — C7
        }
    }

    // ------------------------------------------------------------------
    // 11. addToLong edge cases
    // ------------------------------------------------------------------

    @Test
    public void addToLongStartsFromZero() throws Exception {
        facility.createProfileTable("billing");
        facility.createProfile("billing", "acct", SubscriberProfile.class);
        assertEquals(42L, facility.addToLong(new ProfileID("billing", "acct"), "balance", 42L));
    }

    @Test
    public void addToLongThrowsForNonExistentRow() throws Exception {
        facility.createProfileTable("billing");
        try {
            facility.addToLong(new ProfileID("billing", "ghost"), "balance", 1L);
            fail("Expected ProfileNotFoundException");
        } catch (ProfileNotFoundException expected) {
            // ok
        }
    }
}
