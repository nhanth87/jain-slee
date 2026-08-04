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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of HTTP endpoints contributed by planes (RA, monitor hub, app).
 * Sources {@link #replace} their full list; {@link #snapshot()} merges for the hub table.
 */
public final class HttpEndpointCatalog {

    public static final String SOURCE_HTTP_SERVER_RA = "http-server-ra";
    public static final String SOURCE_MICRO_JAINSLEE = "micro-jainslee";
    public static final String SOURCE_APP = "app";

    private static final HttpEndpointCatalog SHARED = new HttpEndpointCatalog();

    private static final Comparator<HttpEndpointInfo> ORDER =
            Comparator.comparing(HttpEndpointInfo::owner)
                    .thenComparing(HttpEndpointInfo::path)
                    .thenComparing(HttpEndpointInfo::method);

    private final ConcurrentHashMap<String, List<HttpEndpointInfo>> bySource =
            new ConcurrentHashMap<>();

    public static HttpEndpointCatalog shared() {
        return SHARED;
    }

    /** Test helper — empty isolated catalog. */
    public static HttpEndpointCatalog create() {
        return new HttpEndpointCatalog();
    }

    /**
     * Replace all endpoints for {@code sourceId} (fail-fast on blank id).
     * Pass an empty list to clear that source without removing the key.
     */
    public void replace(String sourceId, List<HttpEndpointInfo> endpoints) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId blank");
        }
        bySource.put(sourceId.trim(), List.copyOf(endpoints == null ? List.of() : endpoints));
    }

    public void clear(String sourceId) {
        if (sourceId != null && !sourceId.isBlank()) {
            bySource.remove(sourceId.trim());
        }
    }

    /** Immutable merge of all sources, sorted by owner / path / method. */
    public List<HttpEndpointInfo> snapshot() {
        List<HttpEndpointInfo> out = new ArrayList<>();
        for (List<HttpEndpointInfo> chunk : bySource.values()) {
            out.addAll(chunk);
        }
        out.sort(ORDER);
        return List.copyOf(out);
    }

    public int sourceCount() {
        return bySource.size();
    }
}
