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
 * Second leaf MS service on {@code node-ra} — demonstrates many services per
 * node (n side of service placement) alongside {@link HttpRaService}.
 *
 * <p>Shares the {@code status}/{@code diag} handlers with other services via
 * the n-n {@code SleeServiceHandlerRegistry}.
 */
@SleeService(
        name = "http-aux",
        transport = TransportType.INFINISPAN_QUEUE,
        startPriority = 15)
public final class HttpAuxService implements SleeServiceHandler {

    private static final Logger LOG = LogManager.getLogger(HttpAuxService.class);
    private static final AtomicLong CALLS = new AtomicLong();

    @Override
    public SleeResponse invoke(SleeRequest req) {
        CALLS.incrementAndGet();
        String op = req.operation() == null ? "" : req.operation();
        LOG.info("[http-aux] invoke op={}", op);
        return SleeResponse.ok(("http-aux-handled:" + op).getBytes(StandardCharsets.UTF_8));
    }

    public static long calls() {
        return CALLS.get();
    }

    public static void resetCalls() {
        CALLS.set(0);
    }
}
