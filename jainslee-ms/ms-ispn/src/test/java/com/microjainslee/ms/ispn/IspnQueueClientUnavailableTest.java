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
import com.microjainslee.ms.api.exception.ServiceCallTimeoutException;
import com.microjainslee.ms.api.exception.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IspnQueueClientUnavailableTest {

    private ClusterManager clusterManager;

    @AfterEach
    void tearDown() {
        if (clusterManager != null) {
            clusterManager.stop();
            clusterManager = null;
        }
    }

    @Test
    void callFailsFastWhenServiceNotReady() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "client-node");
        clusterManager.start();
        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.ensureServiceCaches(java.util.List.of("signaling"));

        assertEquals(ServiceState.STOPPED, transport.stateOf("signaling"));

        IspnQueueClient<Void> client = new IspnQueueClient<>("signaling", transport, 500L);
        ServiceUnavailableException ex = assertThrows(
                ServiceUnavailableException.class,
                () -> client.call(new SleeRequest("ping", new byte[0])));
        assertTrue(ex.getMessage().contains("no READY peer"), ex.getMessage());
        assertTrue(ex.getMessage().contains("STOPPED"), ex.getMessage());
    }

    @Test
    void callFailsFastWhenStaleReadyFromDepartedPeer() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "ra-only");
        clusterManager.start();
        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.ensureServiceCaches(java.util.List.of("http-sbb"));

        // Simulate leftover READY published by a peer that is no longer in view.
        transport.stateCache().put(
                "http-sbb",
                new ServiceStateRecord("http-sbb", ServiceState.READY, "node-sbb-gone", System.currentTimeMillis()));

        assertEquals(ServiceState.STOPPED, transport.stateOf("http-sbb"),
                "READY from absent node must not look available");

        IspnQueueClient<Void> client = new IspnQueueClient<>("http-sbb", transport, 500L);
        assertThrows(ServiceUnavailableException.class,
                () -> client.call(new SleeRequest("ping", new byte[0])));
    }

    @Test
    void callTimesOutWhenReadyButNoConsumer() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "lonely-ready");
        clusterManager.start();
        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.ensureServiceCaches(java.util.List.of("signaling"));
        transport.publishState("signaling", ServiceState.READY);
        // No IspnQueueServer — inbox will never be consumed.

        IspnQueueClient<Void> client = new IspnQueueClient<>("signaling", transport, 200L);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.call(new SleeRequest("ping", "x".getBytes(StandardCharsets.UTF_8))));
        assertTrue(
                ex instanceof ServiceCallTimeoutException || ex instanceof ServiceUnavailableException,
                () -> "expected timeout/unavailable, got " + ex);
        assertInstanceOf(ServiceCallTimeoutException.class, ex);
    }
}
