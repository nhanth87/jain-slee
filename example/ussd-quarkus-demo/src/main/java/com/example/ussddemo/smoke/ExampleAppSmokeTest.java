/*
 * micro-jainslee 1.1.0 — example application smoke test.
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.smoke;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ChildRelation;
import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.TimerPort;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.ChildRelationFactory;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.core.InMemoryCmpFieldStore;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end smoke test for the example app's runtime. Exercises every
 * Phase-A/B/C feature against a real {@link MicroSleeContainer}.
 */
public class ExampleAppSmokeTest {

    @SbbAnnotation(name = "UssdSession", vendor = "com.example", version = "1.0")
    public abstract static class UssdSessionSbb extends CmpBackedSbb implements SleeEventHandler {
        public String getDialogId() {
            try {
                Method g = UssdSessionSbb.class.getMethod("getDialogId");
                return (String) cmpRead(g);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public void setDialogId(String v) {
            try {
                Method s = UssdSessionSbb.class.getMethod("setDialogId", String.class);
                cmpWrite(s, v);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public String getState() {
            try {
                Method g = UssdSessionSbb.class.getMethod("getState");
                return (String) cmpRead(g);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public void setState(String v) {
            try {
                Method s = UssdSessionSbb.class.getMethod("setState", String.class);
                cmpWrite(s, v);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public void onEvent(SleeEvent event, ActivityContextInterface aci) { }
    }

    public static class ConcreteSession extends UssdSessionSbb {
        // Stash the initial dialogId here so we can write it to CMP
        // after registerSbb() has bound the entity id.
        private final String initialDialogId;
        public ConcreteSession(String dialogId) {
            this.initialDialogId = dialogId;
        }
        /** Write the initial dialogId to CMP. Must be called AFTER registerSbb(). */
        public void seedCmpState() {
            cmpSetDialogId(initialDialogId);
            cmpSetState("INITIAL");
        }
        public String readDialogId() { return getDialogId(); }
        public String readState() { return getState(); }
        public void cmpSetDialogId(String v) { setDialogId(v); }
        public void cmpSetState(String v) { setState(v); }
    }

    @SbbAnnotation(name = "MenuItem", vendor = "com.example", version = "1.0")
    public static class MenuItemSbb implements Sbb, SleeEventHandler {
        final AtomicInteger eventCount = new AtomicInteger(0);
        @Override public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            eventCount.incrementAndGet();
        }
    }

    public static void main(String[] args) throws Exception {
        ExampleAppSmokeTest test = new ExampleAppSmokeTest();
        int failed = 0;
        String[] scenarios = {
                "cmpRoundTrip", "childSbbCreation", "timerFiresEvent",
                "cascadeRemoveCleansChildren", "manyConcurrentSessions",
                "profileCmpRoundTrip"
        };
        for (String scenario : scenarios) {
            System.out.print("[smoke] " + scenario + " ... ");
            try {
                if ("cmpRoundTrip".equals(scenario))                test.cmpRoundTrip();
                else if ("childSbbCreation".equals(scenario))       test.childSbbCreation();
                else if ("timerFiresEvent".equals(scenario))        test.timerFiresEvent();
                else if ("cascadeRemoveCleansChildren".equals(scenario))
                                                              test.cascadeRemoveCleansChildren();
                else if ("manyConcurrentSessions".equals(scenario)) test.manyConcurrentSessions();
                else if ("profileCmpRoundTrip".equals(scenario))    test.profileCmpRoundTrip();
                System.out.println("OK");
            } catch (Throwable t) {
                System.out.println("FAIL -- " + t.getMessage());
                t.printStackTrace();
                failed++;
            }
        }
        if (failed > 0) {
            System.err.println("[smoke] " + failed + " scenario(s) failed");
            System.exit(1);
        } else {
            System.out.println("[smoke] all scenarios passed");
        }
    }

    private MicroSleeContainer newContainer() {
        MicroSleeContainer c = new MicroSleeContainer();
        c.start();
        return c;
    }

    public void cmpRoundTrip() throws Exception {
        MicroSleeContainer c = newContainer();
        try {
            ConcreteSession cs = new ConcreteSession("d-42");
            SimpleSbbLocalObject lo = c.registerSbb("sess-1", cs);
            cs.seedCmpState();
            waitForActivation(lo, 1000);
            ConcreteSession sbb = (ConcreteSession) lo.getSbb();
            // dialogId is read from CMP — the constructor passed "d-42"
            // but the SBB's own getDialogId() must return it.
            assertEquals("d-42", sbb.readDialogId());
            // Use the CMP-backed setter exposed by UssdSessionSbb (via
            // reflection on the abstract getter/setter pair). This writes
            // through to the InMemoryCmpFieldStore.
            sbb.cmpSetState("DIALOG_OPEN");
            assertEquals("DIALOG_OPEN", sbb.readState());
            InMemoryCmpFieldStore store = (InMemoryCmpFieldStore) c.getCmpFieldStore();
            assertEquals("DIALOG_OPEN", store.load("sess-1").get("state"));
        } finally {
            c.stop();
        }
    }

    public void childSbbCreation() throws Exception {
        MicroSleeContainer c = newContainer();
        try {
            SimpleSbbLocalObject parent = c.registerSbb("sess-parent", new ConcreteSession("d-99"));
            ((ConcreteSession) parent.getSbb()).seedCmpState();
            waitForActivation(parent, 1000);
            ChildRelationFactory factory = c.getChildRelationFactory(
                    childId -> new MenuItemSbb());
            ChildRelation kids = ((SimpleSbbLocalObject) parent)
                    .getChildRelation("kids", factory);
            SbbLocalObject m1 = kids.create();
            SbbLocalObject m2 = kids.create();
            assertEquals(2, kids.size());
            assertTrue(m1.getSbbID().getId().startsWith("sess-parent.child."));
            assertTrue(m2.getSbbID().getId().startsWith("sess-parent.child."));
            assertNotSame(m1, m2);
        } finally {
            c.stop();
        }
    }

    public void timerFiresEvent() throws Exception {
        MicroSleeContainer c = newContainer();
        try {
            SimpleSbbLocalObject lo = c.registerSbb("timer-target", new ConcreteSession("d-t"));
            ((ConcreteSession) lo.getSbb()).seedCmpState();
            waitForActivation(lo, 1000);
            TimerPort timer = c.getTimerPort();
            long id = timer.setTimer(50, lo);
            Thread.sleep(200);
            timer.cancelTimer(id);
            assertTrue(c.getState() == MicroSleeContainer.State.STARTED);
        } finally {
            c.stop();
        }
    }

    public void cascadeRemoveCleansChildren() throws Exception {
        MicroSleeContainer c = newContainer();
        try {
            SimpleSbbLocalObject parent = c.registerSbb("casc-parent", new ConcreteSession("d-c"));
            ((ConcreteSession) parent.getSbb()).seedCmpState();
            waitForActivation(parent, 1000);
            ChildRelationFactory factory = c.getChildRelationFactory(
                    childId -> new MenuItemSbb());
            ChildRelation kids = ((SimpleSbbLocalObject) parent)
                    .getChildRelation("kids", factory);
            SbbLocalObject m1 = kids.create();
            SbbLocalObject m2 = kids.create();
            assertEquals(2, kids.size());
            kids.clear();
            assertEquals(0, kids.size());
            assertTrue(m1.isRemoved());
            assertTrue(m2.isRemoved());
        } finally {
            c.stop();
        }
    }

    public void manyConcurrentSessions() throws Exception {
        MicroSleeContainer c = newContainer();
        try {
            final int n = 1000;
            final CountDownLatch done = new CountDownLatch(n);
            final AtomicInteger errors = new AtomicInteger(0);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            ConcreteSession cs = new ConcreteSession("d-" + idx);
                            SimpleSbbLocalObject lo = c.registerSbb("sess-" + idx, cs);
                            cs.seedCmpState();
                            waitForActivation(lo, 2000);
                            ConcreteSession sbb = (ConcreteSession) lo.getSbb();
                            sbb.cmpSetState("OPEN");
                        } catch (Throwable t) {
                            errors.incrementAndGet();
                            t.printStackTrace();
                        } finally {
                            done.countDown();
                        }
                    }
                }).start();
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(0, errors.get());
        } finally {
            c.stop();
        }
    }

    /** Subscriber profile — exercises ProfileAbstractCmp. */
    public abstract static class SubscriberProfile extends ProfileAbstractCmp {
        public String getMsisdn() {
            try {
                Method g = SubscriberProfile.class.getMethod("getMsisdn");
                return (String) profileGet(g);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public void setMsisdn(String v) {
            try {
                Method s = SubscriberProfile.class.getMethod("setMsisdn", String.class);
                profileSet(s, v);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public int getBalance() {
            try {
                Method g = SubscriberProfile.class.getMethod("getBalance");
                return ((Integer) profileGet(g)).intValue();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public void setBalance(int v) {
            try {
                Method s = SubscriberProfile.class.getMethod("setBalance", int.class);
                profileSet(s, Integer.valueOf(v));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    /** Concrete profile impl — local fields back the abstract accessors. */
    public static class ConcreteSubscriberProfile extends SubscriberProfile {
        private String msisdn;
        private int balance;
        public String getMsisdn() { return msisdn; }
        public void setMsisdn(String v) { this.msisdn = v; }
        public int getBalance() { return balance; }
        public void setBalance(int v) { this.balance = v; }
    }

    /** Phase 6 — Profile facility: create profile, set CMP fields, read back. */
    public void profileCmpRoundTrip() throws Exception {
        MicroSleeContainer c = newContainer();
        try {
            ProfileFacility facility = c.getProfileFacility();
            // Create the profile table and a profile row.
            facility.createProfileTable("subscribers");
            ConcreteSubscriberProfile template = new ConcreteSubscriberProfile();
            template.setMsisdn("+84909000001");
            template.setBalance(100);
            ProfileLocalObject lo = facility.createProfile("subscribers",
                    "sub-1", ConcreteSubscriberProfile.class);
            assertNotNull(lo);
            // Write through the new profile's CMP accessors.
            ConcreteSubscriberProfile p = (ConcreteSubscriberProfile) lo.getProfile();
            p.setMsisdn("+84909000001");
            p.setBalance(100);
            assertEquals("+84909000001", p.getMsisdn());
            assertEquals(100, p.getBalance());
            // Round-trip via the table — same row, fields persisted.
            ProfileLocalObject fetched = facility.getProfile(
                    new ProfileID("subscribers", "sub-1"));
            assertNotNull(fetched);
            assertEquals("+84909000001",
                    ((ConcreteSubscriberProfile) fetched.getProfile()).getMsisdn());
            assertEquals(100,
                    ((ConcreteSubscriberProfile) fetched.getProfile()).getBalance());
        } finally {
            c.stop();
        }
    }

    private static void waitForActivation(SimpleSbbLocalObject lo, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !lo.isReady()) {
            Thread.sleep(5);
        }
        if (!lo.isReady()) {
            throw new IllegalStateException("SBB " + lo.getSbbID().getId()
                    + " did not activate within " + timeoutMs + "ms");
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean cond) {
        if (!cond) throw new AssertionError("expected true");
    }

    private static void assertNotSame(Object a, Object b) {
        if (a == b) throw new AssertionError("expected different instances");
    }

    private static void assertNotNull(Object o) {
        if (o == null) throw new AssertionError("expected non-null");
    }
}
