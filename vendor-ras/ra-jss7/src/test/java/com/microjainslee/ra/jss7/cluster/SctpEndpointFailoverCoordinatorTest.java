/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.SctpEndpointLease;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.core.MicroSleeConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SctpEndpointFailoverCoordinatorTest {

    private ClusterManager manager;
    private Ss7DialogClusterCaches caches;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("ra-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        caches = Ss7DialogClusterCaches.ensureCaches(manager);
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    public void claimPreferredAndReclaimOrphan() {
        String nodeA = manager.getNodeId();
        List<String> endpoints = List.of("10.0.0.1:2905", "10.0.0.2:2905");
        AtomicInteger claims = new AtomicInteger();
        List<String> takeoverEps = new ArrayList<>();

        SctpEndpointFailoverCoordinator coord = new SctpEndpointFailoverCoordinator(
                nodeA,
                endpoints,
                "10.0.0.1:2905",
                caches,
                manager,
                (ep, gen, takeover) -> {
                    claims.incrementAndGet();
                    if (takeover) {
                        takeoverEps.add(ep);
                    }
                });
        assertTrue(coord.claimPreferred());
        assertEquals(nodeA, caches.getEndpointLease("10.0.0.1:2905").ownerNodeId());

        // Simulate departed peer owning second endpoint (node not in local-mode view).
        caches.putEndpointLease(new SctpEndpointLease("10.0.0.2:2905", "dead-node", 0L, 1L));
        List<String> reclaimed = coord.reclaimOrphanedEndpoints();
        assertEquals(List.of("10.0.0.2:2905"), reclaimed);
        assertEquals(List.of("10.0.0.2:2905"), takeoverEps);
        assertEquals(nodeA, caches.getEndpointLease("10.0.0.2:2905").ownerNodeId());
        assertTrue(claims.get() >= 2);
    }
}
