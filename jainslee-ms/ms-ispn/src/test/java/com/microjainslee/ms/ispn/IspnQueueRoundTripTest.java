/*
 * micro-jainslee 1.1.0
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
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IspnQueueRoundTripTest {

    private ClusterManager clusterManager;
    private IspnQueueServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (clusterManager != null) {
            clusterManager.stop();
        }
    }

    @Test
    void callAndNotifyOverLocalCache() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "ms-test-node");
        clusterManager.start();

        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.publishState("signaling", ServiceState.READY);

        server = new IspnQueueServer("signaling", transport, req ->
                SleeResponse.ok(("ok:" + req.operation()).getBytes(StandardCharsets.UTF_8)));
        server.start();

        IspnQueueClient<Void> client = new IspnQueueClient<>("signaling", transport, 5_000L);
        SleeResponse resp = client.call(new SleeRequest("mt-forward", new byte[]{1, 2, 3}));
        assertTrue(resp.success());
        assertEquals("ok:mt-forward", new String(resp.payload(), StandardCharsets.UTF_8));

        client.notify(new SleeRequest("event", new byte[0]));
        // brief yield for async notify processing
        Thread.sleep(100L);
        assertEquals(ServiceState.READY, transport.stateOf("signaling"));
    }
}
