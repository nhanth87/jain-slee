/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.httpclient;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.MarshallingAllowList;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.ra.httpclient.cluster.HttpStickyPost;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HttpClientHaTest {

    private ClusterManager manager;
    private HttpCallbackClientRa ra;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("http-ha-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        ra = new HttpCallbackClientRa();
        ra.setClusterManager(manager);
        ra.raConfigure();
        ra.raActive();
    }

    @After
    public void tearDown() {
        if (ra != null) {
            ra.raStopping();
            ra.raUnconfigure();
        }
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    public void stickyPostMarshallableAndCheckpointBridge() {
        assertNotNull(ra.haSupport());
        AtomicInteger n = new AtomicInteger();
        ra.haSupport().checkpointBridge().bindFunction(id -> {
            n.incrementAndGet();
            return true;
        });
        HttpStickyPost post = new HttpStickyPost("s1", "http://example/x", "{}", "application/json");
        MarshallingAllowList.assertMarshallable("http-sticky", post);
        ra.haSupport().onOpened("s1", "IN_FLIGHT", java.util.Map.of("url", post.url()));
        assertTrue(ra.haSupport().lookupOwner("s1").isPresent());
        assertTrue(ra.haSupport().checkpointSbb("s1"));
        assertEquals(1, n.get());
    }
}
