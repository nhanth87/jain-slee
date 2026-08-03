/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.telemetry;

import com.microjainslee.core.RaObserver;

/**
 * Bridges the core's {@link RaObserver} seam into the passive {@link RaCollector},
 * so {@code jainslee_ra_*} Prometheus metrics are populated from real traffic.
 *
 * <p>Registration (one line after the dispatch observer):
 * <pre>{@code
 * container.setRaObserver(new TelemetryRaObserver(telemetry));
 * }</pre>
 *
 * <p>Cost per call: a few {@link java.util.concurrent.atomic.LongAdder} updates
 * and one {@link java.util.concurrent.ConcurrentHashMap} lookup — no locks, no
 * allocation after first-touch per RA name, in keeping with the zero-CPU
 * telemetry contract.</p>
 */
public final class TelemetryRaObserver implements RaObserver {

    private final TelemetryPort telemetry;

    public TelemetryRaObserver(TelemetryPort telemetry) {
        if (telemetry == null) {
            throw new IllegalArgumentException("telemetry must not be null");
        }
        this.telemetry = telemetry;
    }

    @Override
    public void onEventFired(String raName) {
        telemetry.raCollector().recordEventFired(raName);
    }

    @Override
    public void onCommandSent(String raName) {
        telemetry.raCollector().recordCommand(raName);
    }

    @Override
    public void onFailure(String raName) {
        telemetry.raCollector().recordFailure(raName);
    }
}
