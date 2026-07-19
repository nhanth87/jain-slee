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
import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Invariant I3 — Profile rollback restores field values.
 *
 * <p>Data-safety invariant from PROFILE-IMPLEMENTATION-PLAN.md §6.1:
 * <blockquote>
 * "Rollback event delivery ⇒ profile fields return to old value
 *  (handler throws exception after setBalance)"
 * </blockquote>
 *
 * <p>Test sequence:
 * <ol>
 *   <li>Create profile with {@code balance = 100}.</li>
 *   <li>Start event delivery (transaction active via
 *       {@link ActivityContextTransactionRegistry}).</li>
 *   <li>SBB sets {@code balance = 200} — C3 hook records old value (100)
 *       via {@link SbbTransactionContext#recordProfileWrite}.</li>
 *   <li>SBB throws {@link RuntimeException} → EventRouter calls
 *       {@code handleSbbException} → {@code transaction.rollback()}.</li>
 *   <li>Assert balance restored to 100.</li>
 * </ol>
 */
public class ProfileWriteRollbackTest {

    /** Minimal profile CMP for these tests — balance field only. */
    public static final class BillingProfile extends ProfileAbstractCmp {

        public int getBalance() {
            return (int) (Integer) ProfileAccessorInvoker.getValue(this, getter("balance"));
        }

        public void setBalance(int balance) {
            ProfileAccessorInvoker.setValue(this, setter("balance", int.class), balance);
        }

        private static Method getter(String f) {
            try {
                return BillingProfile.class.getDeclaredMethod(
                        "get" + Character.toUpperCase(f.charAt(0)) + f.substring(1));
            } catch (NoSuchMethodException e) { throw new AssertionError(e); }
        }

        private static Method setter(String f, Class<?> t) {
            try {
                return BillingProfile.class.getDeclaredMethod(
                        "set" + Character.toUpperCase(f.charAt(0)) + f.substring(1), t);
            } catch (NoSuchMethodException e) { throw new AssertionError(e); }
        }
    }

    private MicroSleeContainer container;
    private BillingProfile billingProfile;

    @Before
    public void setUp() throws Exception {
        container = new MicroSleeContainer();
        container.start();

        ProfileFacility facility = container.getProfileFacility();
        facility.createProfileTable("Billing");
        ProfileLocalObject plo = facility.createProfile("Billing", "user1", BillingProfile.class);
        billingProfile = (BillingProfile) plo.getProfile();
        billingProfile.setBalance(100);
        assertEquals("pre-condition: balance=100", 100, billingProfile.getBalance());
    }

    @After
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    // -----------------------------------------------------------------------
    // I3 — rollback restores profile field written during failing event delivery
    // -----------------------------------------------------------------------

    @Test
    public void invariantI3_rollbackRestoresProfileBalance() throws InterruptedException {
        BalanceWritingFailureSbb sbb = new BalanceWritingFailureSbb(billingProfile);
        com.microjainslee.api.SbbLocalObject local = container.registerSbb("billing-sbb", sbb);
        InMemoryActivityContext aci = container.createActivityContext("billing-ac");
        container.attach("billing-ac", local);

        container.routeEvent(new BillingEvent(), aci);

        assertTrue("sbbExceptionThrown must be called within 5s", sbb.awaitHandled());
        assertEquals("I3: rollback must restore balance to 100", 100, billingProfile.getBalance());
    }

    @Test
    public void invariantI3_committedWriteIsNotRolledBack() throws InterruptedException {
        SuccessfulUpdateSbb sbb = new SuccessfulUpdateSbb(billingProfile);
        com.microjainslee.api.SbbLocalObject local = container.registerSbb("billing-ok-sbb", sbb);
        InMemoryActivityContext aci = container.createActivityContext("billing-ok-ac");
        container.attach("billing-ok-ac", local);

        container.routeEvent(new BillingEvent(), aci);

        assertTrue("onEvent must complete within 5s", sbb.awaitDone());
        assertEquals("committed write must survive at balance=200", 200, billingProfile.getBalance());
    }

    @Test
    public void invariantI3_multipleWritesRolledBackInLifoOrder() throws InterruptedException {
        MultiWriteFailureSbb sbb = new MultiWriteFailureSbb(billingProfile);
        com.microjainslee.api.SbbLocalObject local = container.registerSbb("multi-write-sbb", sbb);
        InMemoryActivityContext aci = container.createActivityContext("multi-write-ac");
        container.attach("multi-write-ac", local);

        container.routeEvent(new BillingEvent(), aci);

        assertTrue("sbbExceptionThrown must be called", sbb.awaitHandled());
        // All writes (100→200→300) rolled back → back to 100.
        assertEquals("I3: all intermediate writes rolled back", 100, billingProfile.getBalance());
    }

    // -----------------------------------------------------------------------
    // Inner SBB fixtures
    // -----------------------------------------------------------------------

    private static final class BillingEvent implements SleeEvent {}

    /** Writes balance=200 then throws → should be rolled back to 100. */
    private static final class BalanceWritingFailureSbb implements Sbb, SleeEventHandler {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final BillingProfile profile;

        BalanceWritingFailureSbb(BillingProfile profile) { this.profile = profile; }

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            profile.setBalance(200); // C3 hook records old value (100) here
            throw new RuntimeException("simulated failure after profile write");
        }

        @Override
        public void sbbExceptionThrown(Exception e, Object event, ActivityContextInterface aci) {
            latch.countDown();
        }

        boolean awaitHandled() throws InterruptedException {
            return latch.await(5, TimeUnit.SECONDS);
        }
    }

    /** Writes balance=200 and succeeds → write must persist. */
    private static final class SuccessfulUpdateSbb implements Sbb, SleeEventHandler {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final BillingProfile profile;

        SuccessfulUpdateSbb(BillingProfile profile) { this.profile = profile; }

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            profile.setBalance(200);
            latch.countDown();
        }

        boolean awaitDone() throws InterruptedException {
            return latch.await(5, TimeUnit.SECONDS);
        }
    }

    /** Writes balance multiple times (100→200→300) then throws. */
    private static final class MultiWriteFailureSbb implements Sbb, SleeEventHandler {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final BillingProfile profile;

        MultiWriteFailureSbb(BillingProfile profile) { this.profile = profile; }

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            profile.setBalance(200); // undo(1): 200→100
            profile.setBalance(300); // undo(2): 300→200 applied first (LIFO), then undo(1)
            throw new RuntimeException("simulated failure after multiple profile writes");
        }

        @Override
        public void sbbExceptionThrown(Exception e, Object event, ActivityContextInterface aci) {
            latch.countDown();
        }

        boolean awaitHandled() throws InterruptedException {
            return latch.await(5, TimeUnit.SECONDS);
        }
    }
}
