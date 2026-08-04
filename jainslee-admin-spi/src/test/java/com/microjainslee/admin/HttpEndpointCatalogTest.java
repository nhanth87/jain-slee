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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpEndpointCatalogTest {

    @Test
    public void replaceMergesAndSorts() {
        HttpEndpointCatalog cat = HttpEndpointCatalog.create();
        cat.replace(HttpEndpointCatalog.SOURCE_APP, List.of(
                HttpEndpointInfo.of("GET", "/metrics", "app", "Prometheus scrape"),
                HttpEndpointInfo.of("GET", "/", "app", "landing")));
        cat.replace(HttpEndpointCatalog.SOURCE_MICRO_JAINSLEE, List.of(
                HttpEndpointInfo.of("GET", "/telemetry/*", "micro-jainslee", "hub GUI")));

        List<HttpEndpointInfo> snap = cat.snapshot();
        assertEquals(3, snap.size());
        assertEquals("app", snap.get(0).owner());
        assertEquals("/", snap.get(0).path());
        assertEquals("/metrics", snap.get(1).path());
        assertEquals("micro-jainslee", snap.get(2).owner());
    }

    @Test
    public void replaceIsIdempotentPerSource() {
        HttpEndpointCatalog cat = HttpEndpointCatalog.create();
        cat.replace("app", List.of(HttpEndpointInfo.of("GET", "/a", "app", "")));
        cat.replace("app", List.of(HttpEndpointInfo.of("GET", "/b", "app", "")));
        assertEquals(1, cat.snapshot().size());
        assertEquals("/b", cat.snapshot().get(0).path());
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankMethodFailsFast() {
        HttpEndpointInfo.of(" ", "/x", "app", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankSourceFailsFast() {
        HttpEndpointCatalog.create().replace("  ", List.of());
    }

    @Test
    public void clearRemovesSource() {
        HttpEndpointCatalog cat = HttpEndpointCatalog.create();
        cat.replace("app", List.of(HttpEndpointInfo.of("GET", "/x", "app", "")));
        cat.clear("app");
        assertTrue(cat.snapshot().isEmpty());
        assertEquals(0, cat.sourceCount());
    }
}
