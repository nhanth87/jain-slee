/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.core;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.SleeEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RaObserver} seam: command-port wrap + notify helpers, parallel to
 * {@link DispatchObserverTest}.
 */
public class RaObserverTest {

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(64)
                        .preferVirtualThreads(false)
                        .sbbPerVirtualThread(false)
                        .build());
        container.start();
    }

    @After
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    private static final class RecordingRaObserver implements RaObserver {
        final AtomicInteger fired = new AtomicInteger();
        final AtomicInteger commands = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        volatile String lastRa;

        @Override
        public void onEventFired(String raName) {
            lastRa = raName;
            fired.incrementAndGet();
        }

        @Override
        public void onCommandSent(String raName) {
            lastRa = raName;
            commands.incrementAndGet();
        }

        @Override
        public void onFailure(String raName) {
            lastRa = raName;
            failures.incrementAndGet();
        }
    }

    @Test
    public void commandPortNotifiesObserverOnSend() {
        RecordingRaObserver obs = new RecordingRaObserver();
        container.setRaObserver(obs);
        container.registerRaCommandPort("ra-probe", cmd -> { /* ok */ });

        RaCommandPort port = container.getRaCommandPort("ra-probe");
        port.sendCommand(new OutboundCommand() {});
        assertEquals(1, obs.commands.get());
        assertEquals("ra-probe", obs.lastRa);
        assertEquals(0, obs.failures.get());
    }

    /**
     * Local RAs use {@code registerRa(endpoint, command)} — that path must wrap
     * the command port the same way as {@link MicroSleeContainer#registerRaCommandPort}.
     * Without the wrap, {@code jainslee_ra_commands_sent} stays 0 while events_fired moves.
     */
    @Test
    public void registerRaWrapsCommandPortForObserver() {
        RecordingRaObserver obs = new RecordingRaObserver();
        container.setRaObserver(obs);

        AtomicInteger delegated = new AtomicInteger();
        RaEndpointPort ra = new RaEndpointPort() {
            @Override public String getRaName() { return "ra-local"; }
            @Override public void activate(RaBootstrapPort bootstrap) { }
            @Override public void deactivate() { }
        };
        container.registerRa(ra, cmd -> delegated.incrementAndGet());

        container.getRaCommandPort("ra-local").sendCommand(new OutboundCommand() {});
        assertEquals(1, delegated.get());
        assertEquals(1, obs.commands.get());
        assertEquals("ra-local", obs.lastRa);
        assertEquals(0, obs.failures.get());
    }

    @Test
    public void commandPortNotifiesFailureAndRethrows() {
        RecordingRaObserver obs = new RecordingRaObserver();
        container.setRaObserver(obs);
        container.registerRaCommandPort("ra-probe", cmd -> {
            throw new IllegalStateException("boom");
        });

        try {
            container.getRaCommandPort("ra-probe").sendCommand(new OutboundCommand() {});
            fail("expected boom");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("boom"));
        }
        assertEquals(1, obs.failures.get());
        assertEquals(0, obs.commands.get());
    }

    @Test
    public void throwingObserverDoesNotBreakCommand() {
        container.setRaObserver(new RaObserver() {
            @Override public void onEventFired(String raName) { throw new RuntimeException("obs"); }
            @Override public void onCommandSent(String raName) { throw new RuntimeException("obs"); }
            @Override public void onFailure(String raName) { throw new RuntimeException("obs"); }
        });
        AtomicInteger sent = new AtomicInteger();
        container.registerRaCommandPort("ra-probe", cmd -> sent.incrementAndGet());
        container.getRaCommandPort("ra-probe").sendCommand(new OutboundCommand() {});
        assertEquals(1, sent.get());
    }

    // ── fireEvent path (BootstrapPortAdapter) ────────────────────────────────

    private static final class ProbeEvent implements SleeEvent {}

    /**
     * Minimal RA that fires one event on activation and records how many times
     * it was activated. Used to drive the {@code BootstrapPortAdapter.fireEvent} path.
     */
    private static final class FireOnActivateRa implements RaEndpointPort {
        volatile RaBootstrapPort bootstrap;
        final AtomicInteger activations = new AtomicInteger();

        @Override public String getRaName() { return "ra-fire"; }

        @Override
        public void activate(RaBootstrapPort bootstrap) {
            this.bootstrap = bootstrap;
            activations.incrementAndGet();
            var handle = bootstrap.createActivityHandle("fire-ac");
            bootstrap.fireEvent(new ProbeEvent(), handle, null);
        }

        @Override public void deactivate() { }
    }

    private static void await(java.util.function.BooleanSupplier cond) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !cond.getAsBoolean()) Thread.sleep(20L);
        assertTrue("condition not reached within 10 s", cond.getAsBoolean());
    }

    @Test
    public void fireEventIncrementsOnEventFired() throws Exception {
        RecordingRaObserver obs = new RecordingRaObserver();
        container.setRaObserver(obs);

        FireOnActivateRa ra = new FireOnActivateRa();
        container.registerRa(ra, cmd -> { /* no-op command port */ });

        await(() -> obs.fired.get() >= 1);

        assertEquals(1, obs.fired.get());
        assertEquals("ra-fire", obs.lastRa);
        assertEquals(0, obs.commands.get());
        assertEquals(0, obs.failures.get());
    }

    @Test
    public void clearingObserverStopsCallbacks() {
        RecordingRaObserver obs = new RecordingRaObserver();
        container.setRaObserver(obs);
        container.setRaObserver(null);    // clear

        container.registerRaCommandPort("ra-probe2", cmd -> { });
        container.getRaCommandPort("ra-probe2").sendCommand(new OutboundCommand() {});

        assertEquals(0, obs.commands.get());
        assertNull(container.getRaObserver());
    }
}
