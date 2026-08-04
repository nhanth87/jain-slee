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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared Jackson helpers for RA admin pack APIs (status/config JSON).
 */
public final class RaAdminJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private RaAdminJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static RaAdminHttpResponse ok(Object value) {
        return toResponse(200, value);
    }

    public static RaAdminHttpResponse status(int httpStatus, Object value) {
        return toResponse(httpStatus, value);
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON encode failed: " + ex.getMessage(), ex);
        }
    }

    /** Escape text for HTML fragments (HTMX status partials). */
    public static String escHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static RaAdminHttpResponse toResponse(int httpStatus, Object value) {
        try {
            return RaAdminHttpResponse.json(httpStatus, MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException ex) {
            return RaAdminHttpResponse.error(500, "JSON encode failed: " + ex.getMessage());
        }
    }
}
