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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Result of matching an HTTP request against the SBI catalog. */
public final class SbiRouteMatch {

    private final SbiOperation operation;
    private final Map<String, String> pathParams;

    public SbiRouteMatch(SbiOperation operation, Map<String, String> pathParams) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.pathParams = pathParams == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(pathParams));
    }

    public SbiOperation operation() {
        return operation;
    }

    public Map<String, String> pathParams() {
        return pathParams;
    }
}
