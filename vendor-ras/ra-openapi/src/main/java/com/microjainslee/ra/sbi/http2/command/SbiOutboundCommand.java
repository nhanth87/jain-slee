/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http2.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Outbound SBI call — any catalog operationId or absolute URI. */
public final class SbiOutboundCommand {

    private final String requestId;
    private final String operationId;
    private final String absoluteUri;
    private final String method;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Integer maxRetriesOverride;
    private final String sagaId;
    private final String sagaStepId;
    private final boolean compensate;

    private SbiOutboundCommand(Builder b) {
        this.requestId = b.requestId == null ? UUID.randomUUID().toString() : b.requestId;
        this.operationId = b.operationId;
        this.absoluteUri = b.absoluteUri;
        this.method = b.method;
        this.headers = b.headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.body = b.body == null ? new byte[0] : b.body.clone();
        this.maxRetriesOverride = b.maxRetriesOverride;
        this.sagaId = b.sagaId;
        this.sagaStepId = b.sagaStepId;
        this.compensate = b.compensate;
    }

    public String requestId() { return requestId; }
    public String operationId() { return operationId; }
    public String absoluteUri() { return absoluteUri; }
    public String method() { return method; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body.clone(); }
    public Integer maxRetriesOverride() { return maxRetriesOverride; }
    public String sagaId() { return sagaId; }
    public String sagaStepId() { return sagaStepId; }
    public boolean compensate() { return compensate; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String requestId;
        private String operationId;
        private String absoluteUri;
        private String method = "POST";
        private Map<String, String> headers;
        private byte[] body;
        private Integer maxRetriesOverride;
        private String sagaId;
        private String sagaStepId;
        private boolean compensate;

        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder operationId(String v) { this.operationId = v; return this; }
        public Builder absoluteUri(String v) { this.absoluteUri = v; return this; }
        public Builder method(String v) { this.method = v; return this; }
        public Builder headers(Map<String, String> v) { this.headers = v; return this; }
        public Builder body(byte[] v) { this.body = v; return this; }
        public Builder maxRetriesOverride(Integer v) { this.maxRetriesOverride = v; return this; }
        public Builder sagaId(String v) { this.sagaId = v; return this; }
        public Builder sagaStepId(String v) { this.sagaStepId = v; return this; }
        public Builder compensate(boolean v) { this.compensate = v; return this; }
        public SbiOutboundCommand build() { return new SbiOutboundCommand(this); }
    }
}
