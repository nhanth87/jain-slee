/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.core;

import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.annotations.InjectRa;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * ADR 0004 runtime wiring hardening: P0-2 locator ownership, P0-3 fail-fast
 * {@code @InjectRa} validation, P0-4 container-fed RA state transitions.
 */
public class WiringHardeningTest {

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        System.clearProperty("jainslee.inject-ra.validation");
        container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(64)
                        .preferVirtualThreads(false)
                        .sbbPerVirtualThread(false)
                        .build());
    }

    @After
    public void tearDown() {
        System.clearProperty("jainslee.inject-ra.validation");
        if (container != null) {
            try {
                container.stop();
            } catch (Throwable ignored) {
                // best-effort teardown
            }
        }
    }

    private static final class NamedRa implements RaEndpointPort {
        private final String name;
        private final boolean failActivation;

        NamedRa(String name) { this(name, false); }
        NamedRa(String name, boolean failActivation) {
            this.name = name;
            this.failActivation = failActivation;
        }

        @Override public String getRaName() { return name; }
        @Override public void activate(RaBootstrapPort bootstrap) {
            if (failActivation) {
                throw new IllegalStateException("simulated activation failure");
            }
        }
        @Override public void deactivate() { }
    }

    /** SBB declaring one well-wired and one bogus injection. */
    public static class BrokenWiringSbb implements Sbb {
        @InjectRa(name = "real-ra")
        Object goodPort;
        @InjectRa(name = "no-such-ra")
        Object typoPort;
    }

    /** SBB whose only injection is correct. */
    public static class CorrectWiringSbb implements Sbb {
        @InjectRa(name = "real-ra")
        Object port;
    }

    /** SBB relying on the default (empty-name) port. */
    public static class DefaultWiringSbb implements Sbb {
        @InjectRa
        Object port;
    }

    // ── P0-1 — bridge auto-installed by the container ctor ───────────────────

    @Test
    public void containerCtorInstallsProfileAccessorBridge() throws Exception {
        MicroSleeContainer extra = new MicroSleeContainer(MicroSleeConfiguration.defaults());
        try {
            assertNotNull(com.microjainslee.api.ProfileAccessorInvoker.installed());
            assertTrue(com.microjainslee.api.ProfileAccessorInvoker.installed()
                    instanceof CoreProfileAccessorBridge);
            // ServiceLoader path must also resolve on a plain core+api classpath.
            assertTrue(java.util.ServiceLoader
                    .load(com.microjainslee.api.ProfileAccessorBridge.class)
                    .findFirst().isPresent());
        } finally {
            extra.stop();
        }
    }

    // ── P0-2 — locator ownership is observable ───────────────────────────────

    @Test
    public void globalOwnerTracksTheContainerFacility() {
        assertEquals(container.getProfileFacility(),
                ProfileFieldStoreLocator.globalOwner());

        InMemoryProfileFacility stray = new InMemoryProfileFacility();
        assertNotSame(container.getProfileFacility(),
                ProfileFieldStoreLocator.globalOwner());

        // restore the sanctioned binding so other tests are unaffected
        container.installProfileFacility(
                (InMemoryProfileFacility) container.getProfileFacility());
        assertEquals(container.getProfileFacility(),
                ProfileFieldStoreLocator.globalOwner());
    }

    // ── P0-3 — fail-fast wiring validation ───────────────────────────────────

    @Test
    public void startFailsFastOnInjectRaNameMismatch() {
        container.registerRa(new NamedRa("real-ra"), cmd -> { });
        container.registerSbbType(BrokenWiringSbb.class, BrokenWiringSbb::new);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                container::start);
        String msg = ex.getMessage();
        assertTrue("must name the mismatched field: " + msg,
                msg.contains("BrokenWiringSbb.typoPort"));
        assertTrue("must name the missing ra: " + msg, msg.contains("no-such-ra"));
        assertTrue("must list registered ports for debugging: " + msg,
                msg.contains("real-ra"));
    }

    @Test
    public void warnModeLogsInsteadOfThrowing() {
        System.setProperty("jainslee.inject-ra.validation", "warn");
        container.registerRa(new NamedRa("real-ra"), cmd -> { });
        container.registerSbbType(BrokenWiringSbb.class, BrokenWiringSbb::new);
        container.start(); // must NOT throw in warn mode
        assertEquals(MicroSleeContainer.State.STARTED, container.getState());
    }

    @Test
    public void offModeSkipsValidation() {
        System.setProperty("jainslee.inject-ra.validation", "off");
        container.registerRa(new NamedRa("real-ra"), cmd -> { });
        container.registerSbbType(BrokenWiringSbb.class, BrokenWiringSbb::new);
        container.start();
        assertEquals(MicroSleeContainer.State.STARTED, container.getState());
    }

    @Test
    public void correctlyWiredTypeBootsClean() {
        container.registerRa(new NamedRa("real-ra"), cmd -> { });
        container.registerSbbType(CorrectWiringSbb.class, CorrectWiringSbb::new);
        container.registerSbbType(DefaultWiringSbb.class, DefaultWiringSbb::new);
        container.start();
        assertEquals(MicroSleeContainer.State.STARTED, container.getState());
    }

    @Test
    public void lateRegistrationAfterStartIsAlsoValidated() {
        container.registerRa(new NamedRa("real-ra"), cmd -> { });
        container.start();

        // hot-register an SBB type referencing a not-yet-registered RA
        assertThrows(IllegalStateException.class,
                () -> container.registerSbbType(BrokenWiringSbb.class, BrokenWiringSbb::new));
    }

    /**
     * Regression guard (2026-08-24 E2E lesson): the S5 flow legally registers
     * SBB types BEFORE start() and RAs only AFTER start(). With zero ports at
     * validation time nothing is provable — boot must NOT fail; the typo is
     * reported loudly at injection time instead.
     */
    @Test
    public void zeroPortsAtBootDefersValidationToInjectionTime() {
        container.registerSbbType(BrokenWiringSbb.class, BrokenWiringSbb::new);
        container.start(); // no RA registered yet → deferred, not fatal
        assertEquals(MicroSleeContainer.State.STARTED, container.getState());

        // once ANY ra exists, the mismatch becomes provable → strict again
        assertThrows(IllegalStateException.class, () -> {
            container.registerRa(new NamedRa("unrelated-ra"), cmd -> { });
            container.registerSbbType(BrokenWiringSbb.class, BrokenWiringSbb::new);
        });
    }

    // ── P0-4 — container feeds RA state transitions to the observer ─────────

    private static final class StateRecordingObserver implements RaObserver {
        final List<Map.Entry<String, String>> states = new CopyOnWriteArrayList<>();

        @Override public void onEventFired(String raName) { }
        @Override public void onCommandSent(String raName) { }
        @Override public void onFailure(String raName) { }

        @Override
        public void onStateChange(String raName, String state, int port) {
            states.add(Map.entry(raName, state));
        }
    }

    @Test
    public void registerRaHotActivateFeedsActiveState() {
        StateRecordingObserver obs = new StateRecordingObserver();
        container.setRaObserver(obs);
        container.start();
        container.registerRa(new NamedRa("stateful-ra"), cmd -> { });

        assertTrue("expected ACTIVE transition, got " + obs.states,
                obs.states.contains(Map.entry("stateful-ra", "ACTIVE")));
    }

    @Test
    public void failedActivationFeedsErrorState() {
        StateRecordingObserver obs = new StateRecordingObserver();
        container.setRaObserver(obs);
        container.start();
        container.registerRa(new NamedRa("broken-ra", true), cmd -> { });

        assertTrue("expected ERROR transition, got " + obs.states,
                obs.states.contains(Map.entry("broken-ra", "ERROR")));
    }

    @Test
    public void stopDeactivatesEndpointsWithInactiveState() {
        StateRecordingObserver obs = new StateRecordingObserver();
        container.setRaObserver(obs);
        container.registerRa(new NamedRa("stop-ra"), cmd -> { });
        container.start();
        obs.states.clear();
        container.stop();

        assertTrue("expected INACTIVE on stop, got " + obs.states,
                obs.states.contains(Map.entry("stop-ra", "INACTIVE")));
    }
}
