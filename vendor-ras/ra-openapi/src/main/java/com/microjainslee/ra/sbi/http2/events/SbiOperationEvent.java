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
import com.microjainslee.ra.sbi.openapi.SbiHttpVersion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound 5GC SBI operation — catalog-matched. SBBs implement NF logic;
 * RA only transports/dispatches.
 */
@EventType(name = "SbiOperation", vendor = "com.microjainslee", version = "1.0")
public final class SbiOperationEvent implements SleeEvent {

    private final String sessionId;
    private final String operationId;
    private final String apiName;
    private final String apiVersion;
    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final byte[] body;
    private final SbiHttpVersion httpVersion;
    private final String correlationId;

    public SbiOperationEvent(
            String sessionId,
            String operationId,
            String apiName,
            String apiVersion,
            String method,
            String path,
            Map<String, String> pathParams,
            Map<String, String> queryParams,
            Map<String, String> headers,
            byte[] body,
            SbiHttpVersion httpVersion,
            String correlationId) {
        this.sessionId = sessionId == null ? UUID.randomUUID().toString() : sessionId;
        this.operationId = operationId;
        this.apiName = apiName;
        this.apiVersion = apiVersion;
        this.method = method;
        this.path = path;
        this.pathParams = pathParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(pathParams));
        this.queryParams = queryParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(queryParams));
        this.headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? new byte[0] : body.clone();
        this.httpVersion = httpVersion == null ? SbiHttpVersion.HTTP_2 : httpVersion;
        this.correlationId = correlationId;
    }

    public String sessionId() { return sessionId; }
    public String operationId() { return operationId; }
    public String apiName() { return apiName; }
    public String apiVersion() { return apiVersion; }
    public String method() { return method; }
    public String path() { return path; }
    public Map<String, String> pathParams() { return pathParams; }
    public Map<String, String> queryParams() { return queryParams; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body.clone(); }
    public SbiHttpVersion httpVersion() { return httpVersion; }
    public String correlationId() { return correlationId; }
}
