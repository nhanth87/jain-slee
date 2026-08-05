/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.openapi.gen;

import java.util.List;
import java.util.Objects;

/**
 * Immutable OpenAPI operation row for {@code catalog.json} generation.
 * Field names / shape match {@code SbiOpenApiCatalog} JSON expectations.
 */
public record OperationDescriptor(
        String operationId,
        String method,
        String path,
        String apiName,
        String apiVersion,
        List<String> requestContentTypes,
        List<String> responseContentTypes) {

    public OperationDescriptor {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(apiName, "apiName");
        Objects.requireNonNull(apiVersion, "apiVersion");
        requestContentTypes = requestContentTypes == null || requestContentTypes.isEmpty()
                ? List.of("application/json")
                : List.copyOf(requestContentTypes);
        responseContentTypes = responseContentTypes == null || responseContentTypes.isEmpty()
                ? List.of("application/json", "application/problem+json")
                : List.copyOf(responseContentTypes);
        method = method.toUpperCase();
    }

    public OperationDescriptor withOperationId(String newId) {
        return new OperationDescriptor(
                newId, method, path, apiName, apiVersion,
                requestContentTypes, responseContentTypes);
    }
}
