/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.openapi;

import java.util.List;
import java.util.Objects;

/**
 * One SBI OpenAPI operation — dispatch key for inbound/outbound RA routing.
 */
public final class SbiOperation {

    private final String operationId;
    private final String method;
    private final String pathTemplate;
    private final String apiName;
    private final String apiVersion;
    private final List<String> requestContentTypes;
    private final List<String> responseContentTypes;

    public SbiOperation(
            String operationId,
            String method,
            String pathTemplate,
            String apiName,
            String apiVersion,
            List<String> requestContentTypes,
            List<String> responseContentTypes) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.method = Objects.requireNonNull(method, "method").toUpperCase();
        this.pathTemplate = Objects.requireNonNull(pathTemplate, "pathTemplate");
        this.apiName = apiName == null ? "" : apiName;
        this.apiVersion = apiVersion == null ? "v1" : apiVersion;
        this.requestContentTypes = requestContentTypes == null
                ? List.of("application/json") : List.copyOf(requestContentTypes);
        this.responseContentTypes = responseContentTypes == null
                ? List.of("application/json", "application/problem+json")
                : List.copyOf(responseContentTypes);
    }

    public String operationId() {
        return operationId;
    }

    public String method() {
        return method;
    }

    public String pathTemplate() {
        return pathTemplate;
    }

    public String apiName() {
        return apiName;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public List<String> requestContentTypes() {
        return requestContentTypes;
    }

    public List<String> responseContentTypes() {
        return responseContentTypes;
    }

    public boolean isSafeMethod() {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }
}
