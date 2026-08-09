/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.MarshallingAllowList;
import com.microjainslee.cluster.RaStickyRouter;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SipHaSupportTest {

    private ClusterManager manager;
    private SipServletResourceAdaptor ra;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("sip-ha-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        ra = new SipServletResourceAdaptor();
        ra.setClusterManager(manager);
        SipRaConfig conf = new SipRaConfig();
        conf.setHost("127.0.0.1");
        conf.setUdpPort(0); // avoid bind if possible — still need transports for routeReady
        ra.setConfig(conf);
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
    public void ownershipAndGateACheckpoint() {
        AtomicInteger checkpoints = new AtomicInteger();
        ra.setMicroSleeContainer(new Object() {
            @SuppressWarnings("unused")
            public boolean checkpointSbbEntity(String sbbId) {
                checkpoints.incrementAndGet();
                return true;
            }
        });
        // Local-only HA without starting UDP (initHa via raActive needs ports).
        // Drive haSupport after a minimal active with no transports by calling
        // setClusterManager + reflective init: use raActive with udpPort=0 skipped.
        ra.raActive();
        assertNotNull(ra.haSupport());
        ra.haSupport().checkpointBridge().bindFunction(id -> {
            checkpoints.incrementAndGet();
            return true;
        });
        ra.haSupport().onOpened("call-abc", "Active", java.util.Map.of("peer", "1.2.3.4:5060"));
        assertTrue(ra.haSupport().lookupOwner("call-abc").isPresent());
        MarshallingAllowList.assertMarshallable("sip-meta",
                ra.haSupport().lookupMeta("call-abc").orElseThrow());
        assertEquals(RaStickyRouter.Action.REJECT,
                ra.haSupport().decide("missing", false, true).action());
        assertTrue(ra.haSupport().checkpointSbb("call-abc"));
        assertTrue(checkpoints.get() >= 1);
        SendResponse cmd = new SendResponse("call-abc", 200, "OK");
        assertTrue(cmd instanceof java.io.Serializable);
    }
}
