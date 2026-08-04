/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.jakartaee;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.telemetry.TelemetryDispatchObserver;
import com.microjainslee.telemetry.TelemetryPort;
import com.microjainslee.telemetry.TelemetryRaObserver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper to wire passive telemetry observers on a running container.
 * Optional — applications supply their own {@link TelemetryPort}
 * (typically {@code MicrometerTelemetryPort}) then call
 * {@link #install(MicroSleeContainer, TelemetryPort)} once at bootstrap.
 */
public final class TelemetryObserverSupport {

    private static final Logger LOG = LogManager.getLogger(TelemetryObserverSupport.class);

    private TelemetryObserverSupport() {
    }

    public static void install(MicroSleeContainer container, TelemetryPort telemetry) {
        if (container == null || telemetry == null) {
            throw new IllegalArgumentException("container and telemetry are required");
        }
        container.getEventRouter().setDispatchObserver(new TelemetryDispatchObserver(telemetry));
        container.setRaObserver(new TelemetryRaObserver(telemetry));
        LOG.info("Telemetry dispatch + RA observers installed");
    }
}
