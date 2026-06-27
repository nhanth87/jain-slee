/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.service;

import com.example.ussddemo.spring.proto.MenuResponse;
import com.example.ussddemo.spring.ra.GrpcMenuResourceAdaptor;

/**
 * Test-friendly gRPC menu resolver used by {@link GrpcMenuResourceAdaptor}
 * when no real grpc-simulator is available.
 */
public final class InMemoryGrpcMenuClient {

    private final long latencyMs;

    public InMemoryGrpcMenuClient(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public MenuResponse resolveMenu(String msisdn, String ussdString, String sessionId) {
        if (latencyMs > 0L) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return MenuResponse.newBuilder()
                        .setSessionId(sessionId == null ? "" : sessionId)
                        .setStatus("ERR")
                        .setError("interrupted")
                        .build();
            }
        }
        String menu;
        if ("*123#".equals(ussdString)) {
            menu = "Welcome to micro-jainslee demo\n1. Balance\n2. Buy bundle\n0. Exit";
        } else {
            menu = "Unknown short code " + ussdString + " for " + msisdn;
        }
        return MenuResponse.newBuilder()
                .setSessionId(sessionId == null ? "" : sessionId)
                .setStatus("OK")
                .setMenuText(menu)
                .build();
    }
}
