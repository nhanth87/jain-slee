/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.grpc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.jboss.logging.Logger;

/**
 * Stand-in for a real gRPC billing/menu backend. Spring-managed bean
 * with {@code @Value} property injection.
 */
@Service
public final class MockGrpcMenuClient {

    private static final Logger LOG = Logger.getLogger(MockGrpcMenuClient.class);

    @Value("${ussd.demo.grpc.latency-ms:50}")
    private long latencyMs;

    /**
     * Plain-Java ctor used by the wiring test (no Spring). The Spring
     * variant uses the no-arg ctor + @Value above; for unit tests we
     * need a deterministic latency value.
     */
    public MockGrpcMenuClient(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public MockGrpcMenuClient() {
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
