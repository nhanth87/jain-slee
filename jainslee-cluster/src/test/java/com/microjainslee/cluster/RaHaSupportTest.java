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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RaHaSupportTest {

    private ClusterManager manager;

    @BeforeEach
    void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("ha-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void ownershipMetaAndStickyDecide() {
        RaHaSupport ha = RaHaSupport.create(manager, "sip-servlet");
        ha.onOpened("call-1", "Active", Map.of("peer", "10.0.0.1:5060"));
        assertThat(ha.lookupOwner("call-1")).isPresent();
        assertThat(ha.lookupOwner("call-1").get().ownerNodeId()).isEqualTo(manager.getNodeId());
        assertThat(ha.lookupMeta("call-1").get().attr("peer")).isEqualTo("10.0.0.1:5060");
        MarshallingAllowList.assertMarshallable("meta", ha.lookupMeta("call-1").get());

        RaStickyRouter.Decision local = ha.decide("call-1", false, true);
        assertThat(local.action()).isEqualTo(RaStickyRouter.Action.SEND_LOCAL);

        RaStickyRouter.Decision reject = ha.decide("missing", false, true);
        assertThat(reject.action()).isEqualTo(RaStickyRouter.Action.REJECT);
        assertThat(ha.metrics().stickyRejectCount()).isPositive();
        ha.stopStickyBus();
    }

    @Test
    void gateACheckpointBridge() {
        RaHaSupport ha = RaHaSupport.localOnly("http-client", "node-a");
        AtomicInteger calls = new AtomicInteger();
        ha.checkpointBridge().bindFunction(id -> {
            calls.incrementAndGet();
            return "sbb-1".equals(id);
        });
        assertThat(ha.checkpointSbb("sbb-1")).isTrue();
        assertThat(ha.checkpointSbb("other")).isFalse();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(ha.metrics().raCheckpointOkCount()).isEqualTo(1);
    }

    @Test
    void syncPathDefaultRejectsRemoteOwnerWithoutForward() {
        String old = System.getProperty(RaHaSupport.PROP_STICKY_FORWARD);
        System.clearProperty(RaHaSupport.PROP_STICKY_FORWARD);
        try {
            RaHaSupport ha = RaHaSupport.create(manager, "http-client");
            assertThat(ha.stickyForwardEnabled()).isFalse();
            // Install remote owner directly in cache
            RaActivityOwnerCaches caches = RaActivityOwnerCaches.ensureCaches(manager, "http-client");
            caches.putOwner(new RaDialogOwner("sess-remote", "other-node", "http-client", 0L, 1L));
            RaStickyRouter.Decision d = ha.decide("sess-remote", false, true);
            assertThat(d.action()).isEqualTo(RaStickyRouter.Action.REJECT);
            assertThat(d.reason()).contains("sync-path default");
            assertThat(ha.isLocalOwner("sess-remote")).isFalse();
            ha.stopStickyBus();
        } finally {
            if (old == null) {
                System.clearProperty(RaHaSupport.PROP_STICKY_FORWARD);
            } else {
                System.setProperty(RaHaSupport.PROP_STICKY_FORWARD, old);
            }
        }
    }

    @Test
    void staleOwnerCasFails() {
        RaActivityOwnerCaches caches = RaActivityOwnerCaches.ensureCaches(manager, "diameter");
        RaDialogOwner first = new RaDialogOwner("sess-1", "node-a", "diameter", 0L, 1L);
        assertThat(caches.tryPutOwnerIfAbsent(first)).isTrue();
        assertThat(caches.tryClaimOwnership(first, "node-b", "diameter", 2L)).isTrue();
        assertThat(caches.tryClaimOwnership(first, "node-c", "diameter", 3L)).isFalse();
        assertThat(caches.getOwner("sess-1").ownerNodeId()).isEqualTo("node-b");
        assertThat(caches.getOwner("sess-1").generation()).isEqualTo(1L);
    }
}
