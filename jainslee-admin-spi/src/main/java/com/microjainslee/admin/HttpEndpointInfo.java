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

/**
 * Immutable description of an HTTP route known to the Digicom hub endpoints list.
 *
 * @param method HTTP method or {@code *} / {@code LISTEN}
 * @param path   path or path prefix (e.g. {@code /telemetry/*})
 * @param owner  owning plane: {@code http-server-ra}, {@code micro-jainslee}, {@code app}
 * @param note   short operator-facing hint (never peer-UP fiction)
 */
public record HttpEndpointInfo(String method, String path, String owner, String note) {

    public HttpEndpointInfo {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method required");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path required");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner required");
        }
        method = method.trim();
        path = path.trim();
        owner = owner.trim();
        note = note == null ? "" : note.trim();
    }

    public static HttpEndpointInfo of(String method, String path, String owner, String note) {
        return new HttpEndpointInfo(method, path, owner, note);
    }
}
