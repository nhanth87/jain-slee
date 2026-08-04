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

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-neutral HTTP response for admin pack APIs and the monitor hub.
 */
public final class RaAdminHttpResponse {

    private final int status;
    private final String contentType;
    private final byte[] body;
    private final Map<String, String> headers;

    public RaAdminHttpResponse(int status, String contentType, byte[] body,
                               Map<String, String> headers) {
        this.status = status;
        this.contentType = contentType;
        this.body = body == null ? new byte[0] : body;
        this.headers = headers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public static RaAdminHttpResponse json(String body) {
        return text(200, "application/json; charset=utf-8", body);
    }

    public static RaAdminHttpResponse json(int status, String body) {
        return text(status, "application/json; charset=utf-8", body);
    }

    public static RaAdminHttpResponse text(int status, String contentType, String body) {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return new RaAdminHttpResponse(status, contentType, bytes, Map.of());
    }

    public static RaAdminHttpResponse bytes(String contentType, byte[] body) {
        return new RaAdminHttpResponse(200, contentType, body, Map.of());
    }

    public static RaAdminHttpResponse notFound() {
        return text(404, "text/plain; charset=utf-8", "Not found");
    }

    public static RaAdminHttpResponse redirect(String location) {
        return new RaAdminHttpResponse(302, "text/plain; charset=utf-8", new byte[0],
                Map.of("Location", location == null ? "/" : location));
    }

    public static RaAdminHttpResponse noContent() {
        return new RaAdminHttpResponse(204, null, new byte[0], Map.of());
    }

    public static RaAdminHttpResponse error(int status, String message) {
        return json(status, "{\"error\":\"" + escapeJson(message) + "\"}");
    }

    public RaAdminHttpResponse withHeader(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return new RaAdminHttpResponse(status, contentType, body, merged);
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] body() {
        return body;
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public Map<String, String> headers() {
        return headers;
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
