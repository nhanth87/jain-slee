/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn;

import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Serializable envelope stored in Infinispan inbox/reply caches.
 * Matches the cluster module's Java-serialization marshaller convention.
 */
public final class SleeQueueEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum EntryType {
        REQUEST, RESPONSE, EVENT, ERROR
    }

    private final String correlationId;
    private final EntryType type;
    private final String operation;
    private final String callerNode;
    private final byte[] payload;
    private final String errorMessage;
    private final boolean fireAndForget;
    private final Map<String, String> headers;

    public SleeQueueEntry(
            String correlationId,
            EntryType type,
            String operation,
            String callerNode,
            byte[] payload,
            String errorMessage,
            boolean fireAndForget,
            Map<String, String> headers) {
        this.correlationId = correlationId;
        this.type = type;
        this.operation = operation;
        this.callerNode = callerNode;
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.errorMessage = errorMessage;
        this.fireAndForget = fireAndForget;
        this.headers = headers == null || headers.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(headers));
    }

    public static SleeQueueEntry ofRequest(SleeRequest req, String callerNode, boolean fireAndForget) {
        return new SleeQueueEntry(
                UUID.randomUUID().toString(),
                fireAndForget ? EntryType.EVENT : EntryType.REQUEST,
                req.operation(),
                callerNode,
                req.payload(),
                null,
                fireAndForget,
                req.headers());
    }

    public static SleeQueueEntry ofResponse(String correlationId, SleeResponse response) {
        if (response.success()) {
            return new SleeQueueEntry(
                    correlationId,
                    EntryType.RESPONSE,
                    null,
                    null,
                    response.payload(),
                    null,
                    false,
                    Map.of());
        }
        return ofError(correlationId, response.errorMessage());
    }

    public static SleeQueueEntry ofError(String correlationId, String message) {
        return new SleeQueueEntry(
                correlationId,
                EntryType.ERROR,
                null,
                null,
                new byte[0],
                message == null ? "error" : message,
                false,
                Map.of());
    }

    public SleeRequest toSleeRequest() {
        return new SleeRequest(operation == null ? "" : operation, payload, headers);
    }

    public SleeResponse toSleeResponse() {
        if (type == EntryType.ERROR) {
            return SleeResponse.error(errorMessage == null ? "error" : errorMessage);
        }
        return SleeResponse.ok(payload);
    }

    public String correlationId() {
        return correlationId;
    }

    public EntryType type() {
        return type;
    }

    public String operation() {
        return operation;
    }

    public String callerNode() {
        return callerNode;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean fireAndForget() {
        return fireAndForget;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
