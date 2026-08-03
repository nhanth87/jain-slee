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
import com.microjainslee.ms.api.TransportType;
import com.microjainslee.ms.api.annotation.SleeService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway MS service: HTTP SBB ingress that depends on {@code http-ra}.
 * Calls go Direct (same JVM) or via Infinispan queue (cross-process).
 *
 * <p>Implements {@link SleeServiceHandler} so the jainslee-ms handler
 * registry auto-binds it — no hand-written name switch anywhere.
 */
@SleeService(
        name = "http-sbb",
        transport = TransportType.INFINISPAN_QUEUE,
        dependsOn = {"http-ra"},
        startPriority = 20)
public final class HttpSbbService implements SleeServiceHandler {

    private static final Logger LOG = LogManager.getLogger(HttpSbbService.class);
    private static final AtomicLong CALLS = new AtomicLong();

    @Override
    public SleeResponse invoke(SleeRequest req) {
        CALLS.incrementAndGet();
        String op = req.operation() == null ? "" : req.operation();
        LOG.info("[http-sbb] invoke op={}", op);
        return SleeResponse.ok(("http-sbb-handled:" + op).getBytes(StandardCharsets.UTF_8));
    }

    public static long calls() {
        return CALLS.get();
    }

    public static void resetCalls() {
        CALLS.set(0);
    }
}
