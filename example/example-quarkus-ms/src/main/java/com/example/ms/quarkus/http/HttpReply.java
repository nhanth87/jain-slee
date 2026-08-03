/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-neutral HTTP response produced by handlers and translated by
 * {@code MsGatewaySbb} into an {@code HttpResponseExCommand} on the
 * {@code ra-http-server} command port.
 */
public record HttpReply(int status, String contentType, String text, byte[] binary,
                        Map<String, String> headers) {

    public static HttpReply json(String body) {
        return new HttpReply(200, "application/json", body, null, Map.of());
    }

    public static HttpReply json(int status, String body) {
        return new HttpReply(status, "application/json", body, null, Map.of());
    }

    public static HttpReply text(int status, String body) {
        return new HttpReply(status, "text/plain; charset=utf-8", body, null, Map.of());
    }

    public static HttpReply notFound() {
        return text(404, "Not found");
    }

    public HttpReply withHeader(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return new HttpReply(status, contentType, text, binary, merged);
    }
}
