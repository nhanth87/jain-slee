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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaf MS service hosted with {@code ra-http-server} ingress on {@code node-ra}.
 * Local gateway calls it in-process; remote nodes reach it via Infinispan queue.
 *
 * <p>Implements {@link SleeServiceHandler} so the jainslee-ms handler
 * registry auto-binds it — no hand-written name switch anywhere.
 */
@SleeService(
        name = "http-ra",
        transport = TransportType.INFINISPAN_QUEUE,
        startPriority = 10)
public final class HttpRaService implements SleeServiceHandler {

    private static final Logger LOG = LogManager.getLogger(HttpRaService.class);
    private static final AtomicLong CALLS = new AtomicLong();

    @Override
    public SleeResponse invoke(SleeRequest req) {
        CALLS.incrementAndGet();
        String op = req.operation() == null ? "" : req.operation().toLowerCase(Locale.ROOT);
        String body = new String(req.payload(), StandardCharsets.UTF_8);
        LOG.info("[http-ra] invoke op={} payloadLen={}", op, body.length());
        String reply = switch (op) {
            case "ping" -> "pong";
            case "echo" -> "echo:" + body;
            case "sri-sm" -> "sri-sm-ok:" + body;
            default -> "http-ra:" + op + ":" + body;
        };
        LOG.info("[http-ra] reply op={} result={}", op, reply);
        return SleeResponse.ok(reply.getBytes(StandardCharsets.UTF_8));
    }

    public static long calls() {
        return CALLS.get();
    }

    public static void resetCalls() {
        CALLS.set(0);
    }
}
