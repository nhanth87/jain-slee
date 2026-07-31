/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.api;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-service request envelope. Payload is opaque bytes so RA-specific
 * serializers stay outside ms-api.
 */
public final class SleeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String operation;
    private final byte[] payload;
    private final Map<String, String> headers;

    public SleeRequest(String operation, byte[] payload) {
        this(operation, payload, Map.of());
    }

    public SleeRequest(String operation, byte[] payload, Map<String, String> headers) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.headers = headers == null || headers.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(Map.copyOf(headers));
    }

    public String operation() {
        return operation;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public Map<String, String> headers() {
        return headers;
    }
}
