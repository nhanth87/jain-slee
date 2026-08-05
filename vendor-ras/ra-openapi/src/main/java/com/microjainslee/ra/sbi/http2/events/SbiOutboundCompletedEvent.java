/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http2.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Terminal outcome of an outbound SBI request. */
@EventType(name = "SbiOutboundCompleted", vendor = "com.microjainslee", version = "1.0")
public final class SbiOutboundCompletedEvent implements SleeEvent {

    private final String requestId;
    private final String operationId;
    private final int statusCode;
    private final Map<String, String> headers;
    private final byte[] body;
    private final boolean success;
    private final String error;
    private final int attempts;
    private final String sagaId;

    public SbiOutboundCompletedEvent(
            String requestId,
            String operationId,
            int statusCode,
            Map<String, String> headers,
            byte[] body,
            boolean success,
            String error,
            int attempts,
            String sagaId) {
        this.requestId = requestId;
        this.operationId = operationId;
        this.statusCode = statusCode;
        this.headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? new byte[0] : body.clone();
        this.success = success;
        this.error = error;
        this.attempts = attempts;
        this.sagaId = sagaId;
    }

    public String requestId() { return requestId; }
    public String operationId() { return operationId; }
    public int statusCode() { return statusCode; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body.clone(); }
    public boolean success() { return success; }
    public String error() { return error; }
    public int attempts() { return attempts; }
    public String sagaId() { return sagaId; }
}
