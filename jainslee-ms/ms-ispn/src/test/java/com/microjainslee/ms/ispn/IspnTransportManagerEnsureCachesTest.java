/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IspnTransportManagerEnsureCachesTest {

    private ClusterManager clusterManager;

    @AfterEach
    void tearDown() {
        if (clusterManager != null) {
            clusterManager.stop();
        }
    }

    @Test
    void ensureServiceCachesDefinesAllInboxesOnThisNode() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts("127.0.0.1[18950]")
                .nodeId("node-ra")
                .build();
        clusterManager = new ClusterManager(cfg, null);
        clusterManager.start();

        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.ensureServiceCaches(List.of("http-ra", "http-sbb"));

        var cm = clusterManager.getCacheManager();
        assertTrue(cm.cacheExists(IspnTransportManager.inboxCacheName("http-ra")));
        assertTrue(cm.cacheExists(IspnTransportManager.inboxCacheName("http-sbb")));
        assertTrue(cm.cacheExists(IspnTransportManager.REPLY_CACHE));
        assertTrue(cm.cacheExists(IspnTransportManager.STATE_CACHE));
    }
}
