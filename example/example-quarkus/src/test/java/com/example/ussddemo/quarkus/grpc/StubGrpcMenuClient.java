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

import com.example.ussddemo.quarkus.grpc.proto.MenuResponse;

/** In-memory menu resolver for smoke tests (no gRPC server required). */
public final class StubGrpcMenuClient implements GrpcMenuUpstream {

    private static final String BALANCE_MENU =
            "Welcome to micro-jainslee demo\n1. Balance\n2. Buy bundle\n0. Exit";

    @Override
    public MenuResponse resolveMenu(String msisdn, String ussdString, String sessionId) {
        MenuResponse.Builder b = MenuResponse.newBuilder()
                .setSessionId(sessionId == null ? "" : sessionId);
        if ("*123#".equals(ussdString)) {
            return b.setStatus("OK").setMenuText(BALANCE_MENU).build();
        }
        return b.setStatus("ERR")
                .setError("Unknown short code " + ussdString + " for " + msisdn)
                .build();
    }
}
