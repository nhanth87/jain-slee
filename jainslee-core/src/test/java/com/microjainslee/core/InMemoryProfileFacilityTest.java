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

import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileTable;
import com.microjainslee.api.ProfileTablePort;
import com.microjainslee.api.UnrecognizedProfileTableNameException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end coverage of the Phase-2 profile core: facility creation,
 * row CRUD, reflective CMP access via the {@code ProfileAccessorInvoker}
 * shadow, and the {@code SimpleSbbContext.getProfileFacility()} wiring.
 */
public class InMemoryProfileFacilityTest {

    /**
     * Minimal concrete Profile spec used by the tests below. Each typed
     * accessor routes through the reflective {@code ProfileAccessorInvoker}
     * bridge so we can verify the end-to-end read/write path through the
     * {@link InMemoryProfileFacility}.
     */
    public static final class SubscriberProfile extends ProfileAbstractCmp {
        public String getMsisdn() {
            Method g = findAccessorReflective("msisdn", true);
            return (String) com.microjainslee.api.ProfileAccessorInvoker.getValue(this, g);
        }
        public void setMsisdn(String msisdn) {
            Method s = findAccessorReflective("msisdn", false);
            com.microjainslee.api.ProfileAccessorInvoker.setValue(this, s, msisdn);
        }
        public int getBalance() {
            Method g = findAccessorReflective("balance", true);
            Integer v = (Integer) com.microjainslee.api.ProfileAccessorInvoker.getValue(this, g);
            return v == null ? 0 : v;
        }
        public void setBalance(int balance) {
            Method s = findAccessorReflective("balance", false);
            com.microjainslee.api.ProfileAccessorInvoker.setValue(this, s, balance);
        }

        private static Method findAccessorReflective(String fieldName, boolean getter) {
            String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            String prefix = getter ? "get" : "set";
            try {
                if (getter) {
                    return SubscriberProfile.class.getDeclaredMethod(prefix + capitalized);
                }
                // For setters, infer the parameter type from the getter.
                Method g = SubscriberProfile.class.getDeclaredMethod("get" + capitalized);
                return SubscriberProfile.class.getDeclaredMethod(prefix + capitalized, g.getReturnType());
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("no accessor for " + fieldName, e);
            }
        }
    }

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

    @Test
    public void containerExposesProfileFacility() {
        ProfileFacility pf = container.getProfileFacility();
        assertNotNull(pf);
        assertSame(facility, pf);
    }

    @Test
    public void createProfileTableIsIdempotent() {
        facility.createProfileTable("subscribers");
        facility.createProfileTable("subscribers");
        assertTrue(facility.getProfileTableNames().contains("subscribers"));
    }

    @Test
    public void createProfileOnUnknownTableThrows() {
        try {
            facility.createProfile("ghost", "row1", SubscriberProfile.class);
            fail("Expected UnrecognizedProfileTableNameException");
        } catch (UnrecognizedProfileTableNameException expected) {
            // ok
        } catch (Exception e) {
            fail("Wrong exception type: " + e);
        }
    }

    @Test
    public void createAndFetchProfile() throws Exception {
        facility.createProfileTable("subscribers");
        ProfileLocalObject plo = facility.createProfile(
                "subscribers", "251911000000", SubscriberProfile.class);
        assertNotNull(plo);
        assertEquals("251911000000", plo.getProfileID().getProfileName());
        assertEquals("subscribers", plo.getProfileID().getProfileTableName());

        ProfileLocalObject fetched = facility.getProfile(
                new ProfileID("subscribers", "251911000000"));
        assertNotNull(fetched);
        assertSame(plo.getProfile(), fetched.getProfile());
    }

    @Test
    public void createDuplicateProfileThrows() throws Exception {
        facility.createProfileTable("subscribers");
        facility.createProfile("subscribers", "row1", SubscriberProfile.class);
        try {
            facility.createProfile("subscribers", "row1", SubscriberProfile.class);
            fail("Expected ProfileAlreadyExistsException");
        } catch (ProfileAlreadyExistsException expected) {
            // ok
        }
    }

    @Test
    public void removeProfileClearsRow() throws Exception {
        facility.createProfileTable("subscribers");
        facility.createProfile("subscribers", "row1", SubscriberProfile.class);
        facility.removeProfile(new ProfileID("subscribers", "row1"));
        assertNull(facility.getProfile(new ProfileID("subscribers", "row1")));
    }

    @Test
    public void removeProfileOnUnknownTableThrows() {
        try {
            facility.removeProfile(new ProfileID("ghost", "row1"));
            fail("Expected UnrecognizedProfileTableNameException");
        } catch (UnrecognizedProfileTableNameException expected) {
            // ok
        }
    }

    @Test
    public void reflectiveAccessorReadsAndWritesThroughFacility() throws Exception {
        facility.createProfileTable("subscribers");
        ProfileLocalObject plo = facility.createProfile(
                "subscribers", "251911000000", SubscriberProfile.class);

        Profile profile = plo.getProfile();
        ((SubscriberProfile) profile).setMsisdn("+251911000000");
        ((SubscriberProfile) profile).setBalance(1500);

        assertEquals("+251911000000", ((SubscriberProfile) profile).getMsisdn());
        assertEquals(1500, ((SubscriberProfile) profile).getBalance());

        ProfileLocalObject fresh = facility.getProfile(
                new ProfileID("subscribers", "251911000000"));
        assertEquals("+251911000000", ((SubscriberProfile) fresh.getProfile()).getMsisdn());
        assertEquals(1500, ((SubscriberProfile) fresh.getProfile()).getBalance());
    }

    @Test
    public void profileTableIteratesRows() throws Exception {
        facility.createProfileTable("subscribers");
        facility.createProfile("subscribers", "a", SubscriberProfile.class);
        facility.createProfile("subscribers", "b", SubscriberProfile.class);

        ProfileTable table = facility.getProfileTable("subscribers");
        assertNotNull(table);
        assertEquals(2, table.getProfileCount());

        Set<String> names = new HashSet<String>();
        for (ProfileLocalObject plo : table.getProfiles()) {
            names.add(plo.getProfileID().getProfileName());
        }
        assertEquals(2, names.size());
        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
        assertTrue(table.containsProfile("a"));
        assertFalse(table.containsProfile("missing"));
    }

    @Test
    public void defaultValueReturnedForUnsetField() throws Exception {
        facility.createProfileTable("subscribers");
        ProfileLocalObject plo = facility.createProfile(
                "subscribers", "row1", SubscriberProfile.class);
        assertEquals(0, ((SubscriberProfile) plo.getProfile()).getBalance());
    }

    @Test
    public void getProfileTableNamesReflectsLiveTables() {
        facility.createProfileTable("a");
        facility.createProfileTable("b");
        facility.createProfileTable("c");
        assertEquals(3, facility.getProfileTableNames().size());

        facility.removeProfileTable("b");
        Set<String> remaining = facility.getProfileTableNames();
        assertEquals(2, remaining.size());
        assertFalse(remaining.contains("b"));
    }

    @Test
    public void sbbContextExposesProfileFacilityThroughLegacyPort() throws Exception {
        ProfileTablePort port = new SimpleSbbContext(
                new com.microjainslee.api.ServiceID("svc", "com.microjainslee", "1.0"),
                null, new com.microjainslee.api.SbbID("Test"),
                container.getTimerPort(),
                container.getActivityContextNamingFacility(),
                facility).getProfileFacility();
        assertNotNull(port);
        assertSame(facility, port);
        port.createProfileTable("legacy");
        port.createProfile("legacy", "r1", SubscriberProfile.class);
        assertNotNull(port.getProfile(new ProfileID("legacy", "r1")));
    }

    @Test
    public void fieldNameForUnwrapsAccessors() throws Exception {
        Method getter = SubscriberProfile.class.getDeclaredMethod("getMsisdn");
        Method setter = SubscriberProfile.class.getDeclaredMethod("setMsisdn", String.class);
        assertEquals("msisdn",
                com.microjainslee.api.ProfileAccessorInvoker.fieldNameFor(getter));
        assertEquals("msisdn",
                com.microjainslee.api.ProfileAccessorInvoker.fieldNameFor(setter));
    }

    @Test
    public void stopReleasesProfileLocator() {
        container.stop();
        assertNull(ProfileFieldStoreLocator.get());
        container = null;
    }
}