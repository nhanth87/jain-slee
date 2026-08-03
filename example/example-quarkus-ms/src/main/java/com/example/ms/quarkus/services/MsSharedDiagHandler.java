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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Programmatic n-n handler: a <em>single</em> instance registered for
 * {@code diag} on many services (beats self-handlers via programmatic tier).
 */
public final class MsSharedDiagHandler implements SleeServiceHandler {

    private static final AtomicLong CALLS = new AtomicLong();

    @Override
    public SleeResponse invoke(SleeRequest req) {
        CALLS.incrementAndGet();
        return SleeResponse.ok("shared-diag".getBytes(StandardCharsets.UTF_8));
    }

    public static long calls() {
        return CALLS.get();
    }

    public static void resetCalls() {
        CALLS.set(0);
    }
}
