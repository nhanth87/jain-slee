/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;
import com.microjainslee.ms.api.SleeResponse;

import java.util.concurrent.CompletableFuture;

/**
 * Local SLEE event delivered to {@code MsAppBridgeSbb}, which invokes the
 * {@code signaling} microservice via {@code SleeServiceClient}.
 *
 * <p>Callers that are not already inside an EventRouter worker may await
 * {@link #response()}. Never {@code routeEvent}+wait from inside another SBB
 * {@code onEvent} on a shared disruptor worker — that deadlocks.</p>
 */
@EventType(name = "MsServiceCall", vendor = "com.example.ms", version = "1.0")
public final class MsServiceCallEvent implements SleeEvent {

    private final String operation;
    private final byte[] payload;
    private final boolean notifyOnly;
    private final CompletableFuture<SleeResponse> response = new CompletableFuture<>();

    public MsServiceCallEvent(String operation, byte[] payload, boolean notifyOnly) {
        this.operation = operation == null ? "" : operation;
        this.payload = payload == null ? new byte[0] : payload;
        this.notifyOnly = notifyOnly;
    }

    public String operation() {
        return operation;
    }

    public byte[] payload() {
        return payload;
    }

    public boolean notifyOnly() {
        return notifyOnly;
    }

    public CompletableFuture<SleeResponse> response() {
        return response;
    }
}
