/*
 * micro-jainslee 1.2.0
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
 * Demo handlers for {@code http-ra} and {@code http-sbb}. Business code stays
 * free of Direct/ISPN details — the runtime picks the transport.
 */
public final class ServiceHandlers {

    private static final AtomicLong HTTP_RA_CALLS = new AtomicLong();
    private static final AtomicLong HTTP_SBB_CALLS = new AtomicLong();

    private ServiceHandlers() {
    }

    public static SleeServiceHandler forDescriptor(SleeServiceDescriptor desc) {
        return switch (desc.name()) {
            case "http-ra" -> ServiceHandlers::handleHttpRa;
            case "http-sbb" -> ServiceHandlers::handleHttpSbb;
            default -> req -> SleeResponse.error("unknown service: " + desc.name());
        };
    }

    public static long httpRaCalls() {
        return HTTP_RA_CALLS.get();
    }

    public static long httpSbbCalls() {
        return HTTP_SBB_CALLS.get();
    }

    /** @deprecated use {@link #httpRaCalls()} */
    @Deprecated
    public static long signalingCalls() {
        return httpRaCalls();
    }

    /** @deprecated use {@link #httpSbbCalls()} */
    @Deprecated
    public static long appCalls() {
        return httpSbbCalls();
    }

    public static void resetCounters() {
        HTTP_RA_CALLS.set(0);
        HTTP_SBB_CALLS.set(0);
    }

    private static SleeResponse handleHttpRa(SleeRequest req) {
        HTTP_RA_CALLS.incrementAndGet();
        String op = req.operation() == null ? "" : req.operation().toLowerCase(Locale.ROOT);
        String body = new String(req.payload(), StandardCharsets.UTF_8);
        String reply = switch (op) {
            case "ping" -> "pong";
            case "echo" -> "echo:" + body;
            case "sri-sm" -> "sri-sm-ok:" + body;
            default -> "http-ra:" + op + ":" + body;
        };
        return SleeResponse.ok(reply.getBytes(StandardCharsets.UTF_8));
    }

    private static SleeResponse handleHttpSbb(SleeRequest req) {
        HTTP_SBB_CALLS.incrementAndGet();
        String op = req.operation() == null ? "" : req.operation();
        return SleeResponse.ok(("http-sbb-handled:" + op).getBytes(StandardCharsets.UTF_8));
    }
}
