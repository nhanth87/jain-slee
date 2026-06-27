/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.embedded;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Stand-in for a real gRPC billing/menu backend. Plain Java (no CDI)
 * with constructor-injected latency instead of {@code @ConfigProperty}.
 */
public final class MockGrpcMenuClient {

    private static final Logger LOG = LogManager.getLogger(MockGrpcMenuClient.class);

    private final long latencyMs;

    public MockGrpcMenuClient(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String fetchMenu(String msisdn, String ussdString) {
        LOG.info("[mock-gRPC] ResolveMenu msisdn={} ussd={}", msisdn, ussdString);
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
