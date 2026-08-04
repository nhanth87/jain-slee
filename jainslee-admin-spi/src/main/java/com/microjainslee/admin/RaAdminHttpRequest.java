/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.admin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-neutral HTTP request for admin pack APIs and the monitor hub.
 * Apps adapt from {@code HttpWebRequestEvent} / Vert.x / Quarkus without
 * pulling transport types into this SPI.
 */
public final class RaAdminHttpRequest {

    private final String method;
    private final String path;
    private final String body;
    private final Map<String, String> query;

    public RaAdminHttpRequest(String method, String path, String body, Map<String, String> query) {
        this.method = method == null ? "GET" : method;
        this.path = path == null ? "/" : path;
        this.body = body;
        this.query = query == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(query));
    }

    public static RaAdminHttpRequest of(String method, String path, String body) {
        return new RaAdminHttpRequest(method, path, body, Map.of());
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String body() {
        return body;
    }

    public Map<String, String> query() {
        return query;
    }

    public String queryParam(String name) {
        return query.get(name);
    }
}
