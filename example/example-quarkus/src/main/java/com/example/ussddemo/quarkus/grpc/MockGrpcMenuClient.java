/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.grpc;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Stand-in for a real gRPC billing/menu backend. CDI + MicroProfile Config. */
@ApplicationScoped
@Unremovable
public final class MockGrpcMenuClient {

    private static final Logger LOG = Logger.getLogger(MockGrpcMenuClient.class);

    @ConfigProperty(name = "ussd.demo.grpc.latency-ms", defaultValue = "50")
    long latencyMs;

    /**
     * Plain-Java ctor used by the wiring test (no CDI). The CDI variant
     * uses the no-arg ctor + @ConfigProperty above; for unit tests we
     * need a deterministic latency value.
     */
    public MockGrpcMenuClient(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String fetchMenu(String msisdn, String ussdString) {
        LOG.infof("[mock-gRPC] ResolveMenu msisdn=%s ussd=%s", msisdn, ussdString);
        if (latencyMs > 0L) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during mock gRPC call", e);
            }
        }
        if ("*123#".equals(ussdString)) {
            return "Welcome to micro-jainslee demo\n1. Balance\n2. Buy bundle\n0. Exit";
        }
        return "Unknown short code " + ussdString + " for " + msisdn;
    }
}
