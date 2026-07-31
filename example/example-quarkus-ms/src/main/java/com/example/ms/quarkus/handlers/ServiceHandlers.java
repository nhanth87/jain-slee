/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.handlers;

import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo handlers for {@code signaling} and {@code app}. Business code stays
 * free of Direct/ISPN details — the runtime picks the transport.
 */
public final class ServiceHandlers {

    private static final AtomicLong SIGNALING_CALLS = new AtomicLong();
    private static final AtomicLong APP_CALLS = new AtomicLong();

    private ServiceHandlers() {
    }

    public static SleeServiceHandler forDescriptor(SleeServiceDescriptor desc) {
        return switch (desc.name()) {
            case "signaling" -> ServiceHandlers::handleSignaling;
            case "app" -> ServiceHandlers::handleApp;
            default -> req -> SleeResponse.error("unknown service: " + desc.name());
        };
    }

    public static long signalingCalls() {
        return SIGNALING_CALLS.get();
    }

    public static long appCalls() {
        return APP_CALLS.get();
    }

    public static void resetCounters() {
        SIGNALING_CALLS.set(0);
        APP_CALLS.set(0);
    }

    private static SleeResponse handleSignaling(SleeRequest req) {
        SIGNALING_CALLS.incrementAndGet();
        String op = req.operation() == null ? "" : req.operation().toLowerCase(Locale.ROOT);
        String body = new String(req.payload(), StandardCharsets.UTF_8);
        String reply = switch (op) {
            case "ping" -> "pong";
            case "echo" -> "echo:" + body;
            case "sri-sm" -> "sri-sm-ok:" + body;
            default -> "signaling:" + op + ":" + body;
        };
        return SleeResponse.ok(reply.getBytes(StandardCharsets.UTF_8));
    }

    private static SleeResponse handleApp(SleeRequest req) {
        APP_CALLS.incrementAndGet();
        String op = req.operation() == null ? "" : req.operation();
        return SleeResponse.ok(("app-handled:" + op).getBytes(StandardCharsets.UTF_8));
    }
}
