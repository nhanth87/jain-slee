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
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.SleeServiceHandlerProvider;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ServiceLoader n-n provider: <em>one</em> provider contributes the {@code diag}
 * operation to <em>many</em> services ({@code http-ra}, {@code http-sbb},
 * {@code http-aux}). Self-handlers remain the wildcard fallback for other ops.
 */
public final class MsSharedDiagProvider implements SleeServiceHandlerProvider {

    private static final AtomicLong CALLS = new AtomicLong();

    @Override
    public Collection<String> serviceNames() {
        return List.of("http-ra", "http-sbb", "http-aux");
    }

    @Override
    public Collection<String> operations(String serviceName) {
        return List.of("diag");
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public SleeServiceHandler create(SleeServiceDescriptor descriptor) {
        return MsSharedDiagProvider::handle;
    }

    /** Shared handler body (also used by {@link MsSharedDiagHandler} facade). */
    static SleeResponse handle(SleeRequest req) {
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
