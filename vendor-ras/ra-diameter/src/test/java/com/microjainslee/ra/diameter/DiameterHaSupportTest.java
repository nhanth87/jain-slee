/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.SctpEndpointLease;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.core.MicroSleeConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DiameterHaSupportTest {

    private ClusterManager manager;
    private DiameterResourceAdaptor ra;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("dia-ha-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        ra = new DiameterResourceAdaptor();
        DiameterRaConfig conf = new DiameterRaConfig();
        conf.setHost("127.0.0.1");
        conf.setPort(3868);
        conf.setTcpEnabled(false); // no Netty bind in unit test
        ra.setConfig(conf);
        ra.setClusterManager(manager);
        ra.raConfigure();
        ra.raActive();
    }

    @After
    public void tearDown() {
        if (ra != null) {
            ra.raInactive();
        }
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    public void sessionOwnershipCheckpointAndEndpointLease() {
        assertNotNull(ra.haSupport());
        AtomicInteger n = new AtomicInteger();
        ra.haSupport().checkpointBridge().bindFunction(id -> {
            n.incrementAndGet();
            return true;
        });
        ra.haSupport().onOpened("sess;123", "Active", java.util.Map.of("peerId", "p1"));
        assertTrue(ra.haSupport().lookupOwner("sess;123").isPresent());
        assertTrue(ra.haSupport().checkpointSbb("sess;123"));
        assertEquals(1, n.get());

        Ss7DialogClusterCaches caches = Ss7DialogClusterCaches.ensureCaches(manager);
        SctpEndpointLease lease = caches.getEndpointLease("127.0.0.1:3868");
        assertNotNull(lease);
        assertEquals(manager.getNodeId(), lease.ownerNodeId());
    }
}
