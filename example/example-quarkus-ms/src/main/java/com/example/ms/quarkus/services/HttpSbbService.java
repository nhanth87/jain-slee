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
 * Business MS service on {@code node-sbb}. Ingress is <em>not</em> here —
 * the RA node gateway reaches this service via Infinispan queue
 * ({@code /api/demo/call-sbb} on :8081).
 *
 * <p>Implements {@link SleeServiceHandler} so the jainslee-ms handler
 * registry auto-binds it — no hand-written name switch anywhere.
 *
 * <p>Receive logs appear in <strong>stdout of {@code run-ms-sbb.sh}</strong>
 * (port 8082 is /health only — not the place to look for invoke logs).
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
        long n = CALLS.incrementAndGet();
        String op = req.operation() == null ? "" : req.operation();
        int payloadLen = req.payload() == null ? 0 : req.payload().length;
        LOG.info("[http-sbb] invoke op={} call#{} payloadLen={}", op, n, payloadLen);
        String body = "http-sbb-handled:" + op;
        LOG.info("[http-sbb] reply op={} result={} call#{}", op, body, n);
        return SleeResponse.ok(body.getBytes(StandardCharsets.UTF_8));
    }

    public static long calls() {
        return CALLS.get();
    }

    public static void resetCalls() {
        CALLS.set(0);
    }
}
