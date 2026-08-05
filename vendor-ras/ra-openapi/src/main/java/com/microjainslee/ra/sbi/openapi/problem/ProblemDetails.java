/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.openapi.problem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * RFC 7807 Problem Details as used on 5GC SBI (3GPP TS 29.500).
 */
public final class ProblemDetails {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String type;
    private final String title;
    private final int status;
    private final String detail;
    private final String instance;
    private final String cause;

    public ProblemDetails(String type, String title, int status, String detail,
                          String instance, String cause) {
        this.type = type == null ? "about:blank" : type;
        this.title = title;
        this.status = status;
        this.detail = detail;
        this.instance = instance;
        this.cause = cause;
    }

    public static ProblemDetails of(int status, String title, String detail, String cause) {
        return new ProblemDetails("about:blank", title, status, detail, null, cause);
    }

    public String type() { return type; }
    public String title() { return title; }
    public int status() { return status; }
    public String detail() { return detail; }
    public String instance() { return instance; }
    public String cause() { return cause; }

    public String toJson() {
        try {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("type", type);
            if (title != null) {
                n.put("title", title);
            }
            n.put("status", status);
            if (detail != null) {
                n.put("detail", detail);
            }
            if (instance != null) {
                n.put("instance", instance);
            }
            if (cause != null) {
                n.put("cause", cause);
            }
            return MAPPER.writeValueAsString(n);
        } catch (Exception e) {
            return "{\"status\":" + status + ",\"title\":\"" + title + "\"}";
        }
    }

    public static final String CONTENT_TYPE = "application/problem+json";
}
