/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.httpclient.cluster;

import java.io.Serializable;
import java.util.Objects;

/** Portable sticky payload for HTTP client RA (ADR 0002). */
public record HttpStickyPost(String sessionId, String url, String body, String contentType,
                             java.util.Map<String, String> headers)
        implements Serializable {

    public HttpStickyPost(String sessionId, String url, String body, String contentType) {
        this(sessionId, url, body, contentType, java.util.Map.of());
    }

    public HttpStickyPost {
        headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(url, "url");
        body = body == null ? "" : body;
        contentType = contentType == null || contentType.isBlank()
                ? "application/json" : contentType;
    }
}
