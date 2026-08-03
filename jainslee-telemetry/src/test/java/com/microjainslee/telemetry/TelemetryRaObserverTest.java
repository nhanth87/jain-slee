/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.telemetry;

import org.junit.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link TelemetryRaObserver} delegates each callback to the right
 * {@link RaCollector} method. Uses a real {@link RaCollector} instance
 * so the assertions are exact counter values, not mocks.
 */
public class TelemetryRaObserverTest {

    /** Minimal TelemetryPort that exposes only a real RaCollector. */
    private static final class RaOnlyPort implements TelemetryPort {
        final RaCollector ra = new RaCollector();

        @Override public RaCollector raCollector() { return ra; }

        @Override public SbbCollector sbbCollector()       { throw new UnsupportedOperationException(); }
        @Override public ErrorCollector errorCollector()   { throw new UnsupportedOperationException(); }
        @Override public SpunkDetector spunkDetector()     { throw new UnsupportedOperationException(); }
        @Override public StaleDetector staleDetector()     { throw new UnsupportedOperationException(); }
        @Override public ResourceMonitor resourceMonitor() { throw new UnsupportedOperationException(); }
        @Override public AlarmEngine alarmEngine()         { throw new UnsupportedOperationException(); }
        @Override public AutoReconfigEngine autoReconfig() { throw new UnsupportedOperationException(); }
        @Override public boolean isAutoReconfigEnabled()   { return false; }
        @Override public void setAutoReconfigEnabled(boolean enabled) { }
        @Override public String scrape()                   { return ""; }
        @Override public Counter customCounter(String name, String... tagPairs) { throw new UnsupportedOperationException(); }
        @Override public Gauge customGauge(String name, Supplier<Number> supplier, String... tagPairs) { throw new UnsupportedOperationException(); }
        @Override public TelemetrySnapshot snapshot()      { throw new UnsupportedOperationException(); }
    }

    @Test
    public void onEventFiredIncrementsRaEventsFired() {
        RaOnlyPort port = new RaOnlyPort();
        TelemetryRaObserver observer = new TelemetryRaObserver(port);

        observer.onEventFired("ra-ss7");
        observer.onEventFired("ra-ss7");
        observer.onEventFired("ra-http");

        List<RaCollector.RaStats> stats = port.ra.stats();
        RaCollector.RaStats ss7 = stats.stream()
                .filter(s -> "ra-ss7".equals(s.raName())).findFirst().orElseThrow();
        RaCollector.RaStats http = stats.stream()
                .filter(s -> "ra-http".equals(s.raName())).findFirst().orElseThrow();

        assertEquals(2, ss7.eventsFired());
        assertEquals(1, http.eventsFired());
        assertEquals(0, ss7.commandsSent());
        assertEquals(0, ss7.failures());
    }

    @Test
    public void onCommandSentIncrementsCommandsSent() {
        RaOnlyPort port = new RaOnlyPort();
        TelemetryRaObserver observer = new TelemetryRaObserver(port);

        observer.onCommandSent("ra-smpp");
        observer.onCommandSent("ra-smpp");
        observer.onCommandSent("ra-smpp");

        List<RaCollector.RaStats> stats = port.ra.stats();
        RaCollector.RaStats smpp = stats.stream()
                .filter(s -> "ra-smpp".equals(s.raName())).findFirst().orElseThrow();

        assertEquals(3, smpp.commandsSent());
        assertEquals(0, smpp.eventsFired());
        assertEquals(0, smpp.failures());
    }

    @Test
    public void onFailureIncrementsFailures() {
        RaOnlyPort port = new RaOnlyPort();
        TelemetryRaObserver observer = new TelemetryRaObserver(port);

        observer.onFailure("ra-ss7");

        List<RaCollector.RaStats> stats = port.ra.stats();
        RaCollector.RaStats ss7 = stats.stream()
                .filter(s -> "ra-ss7".equals(s.raName())).findFirst().orElseThrow();

        assertEquals(1, ss7.failures());
        assertEquals(0, ss7.eventsFired());
        assertEquals(0, ss7.commandsSent());
    }

    @Test
    public void allThreeCountersAccumulateIndependently() {
        RaOnlyPort port = new RaOnlyPort();
        TelemetryRaObserver observer = new TelemetryRaObserver(port);

        observer.onEventFired("ra-x");
        observer.onEventFired("ra-x");
        observer.onCommandSent("ra-x");
        observer.onFailure("ra-x");
        observer.onFailure("ra-x");
        observer.onFailure("ra-x");

        List<RaCollector.RaStats> stats = port.ra.stats();
        RaCollector.RaStats x = stats.stream()
                .filter(s -> "ra-x".equals(s.raName())).findFirst().orElseThrow();

        assertEquals(2, x.eventsFired());
        assertEquals(1, x.commandsSent());
        assertEquals(3, x.failures());
    }

    @Test
    public void differentRaNamesAreTrackedSeparately() {
        RaOnlyPort port = new RaOnlyPort();
        TelemetryRaObserver observer = new TelemetryRaObserver(port);

        observer.onEventFired("ra-a");
        observer.onEventFired("ra-b");
        observer.onEventFired("ra-b");

        List<RaCollector.RaStats> stats = port.ra.stats();
        assertTrue(stats.stream().anyMatch(s -> "ra-a".equals(s.raName()) && s.eventsFired() == 1));
        assertTrue(stats.stream().anyMatch(s -> "ra-b".equals(s.raName()) && s.eventsFired() == 2));
    }
}
