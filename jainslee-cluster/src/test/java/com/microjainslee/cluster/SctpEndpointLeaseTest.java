/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import com.microjainslee.core.MicroSleeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SctpEndpointLeaseTest {

    private ClusterManager manager;
    private Ss7DialogClusterCaches caches;

    @BeforeEach
    void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("ep-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        caches = Ss7DialogClusterCaches.ensureCaches(manager);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void putIfAbsentAndCasClaimBumpGeneration() {
        SctpEndpointLease first = new SctpEndpointLease("10.0.0.1:2905", "node-a", 0L, 1L);
        assertThat(caches.tryPutEndpointLeaseIfAbsent(first)).isTrue();
        assertThat(caches.tryPutEndpointLeaseIfAbsent(
                new SctpEndpointLease("10.0.0.1:2905", "node-b", 0L, 2L))).isFalse();

        assertThat(caches.tryClaimEndpointLease(first, "node-b", 3L)).isTrue();
        SctpEndpointLease after = caches.getEndpointLease("10.0.0.1:2905");
        assertThat(after).isNotNull();
        assertThat(after.ownerNodeId()).isEqualTo("node-b");
        assertThat(after.generation()).isEqualTo(1L);
    }
}
