/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.services;

import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceHandler;

/**
 * Thin handler facade kept for counters/diagnostics endpoints.
 * Prefer {@link MsSharedDiagProvider} (ServiceLoader SPI) for registration.
 */
public final class MsSharedDiagHandler implements SleeServiceHandler {

    @Override
    public SleeResponse invoke(SleeRequest req) {
        return MsSharedDiagProvider.handle(req);
    }

    public static long calls() {
        return MsSharedDiagProvider.calls();
    }

    public static void resetCalls() {
        MsSharedDiagProvider.resetCalls();
    }
}
