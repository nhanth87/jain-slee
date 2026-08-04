/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn.ra;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Inbound MS request delivered via {@code ispn-queue-ra} EVENT mode.
 * SBB completes {@link #response()} (or uses {@link IspnQueueCommand.ReplyRemoteRequest}).
 */
public final class MsRemoteRequestEvent implements SleeEvent {

    private final String serviceName;
    private final String correlationId;
    private final SleeRequest request;
    private final boolean fireAndForget;
    private final CompletableFuture<SleeResponse> response;

    public MsRemoteRequestEvent(
            String serviceName,
            String correlationId,
            SleeRequest request,
            boolean fireAndForget) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.request = Objects.requireNonNull(request, "request");
        this.fireAndForget = fireAndForget;
        this.response = new CompletableFuture<>();
    }

    public String serviceName() {
        return serviceName;
    }

    public String correlationId() {
        return correlationId;
    }

    public SleeRequest request() {
        return request;
    }

    public boolean fireAndForget() {
        return fireAndForget;
    }

    public CompletableFuture<SleeResponse> response() {
        return response;
    }
}
