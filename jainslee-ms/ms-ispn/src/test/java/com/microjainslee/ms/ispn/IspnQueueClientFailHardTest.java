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
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.exception.ServiceCallTimeoutException;
import com.microjainslee.ms.api.exception.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IspnQueueClientFailHardTest {

    private ClusterManager clusterManager;

    @AfterEach
    void tearDown() {
        if (clusterManager != null) {
            clusterManager.stop();
        }
    }

    @Test
    void callRefusesWhenPeerStopped() {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "fail-hard");
        clusterManager.start();
        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.publishState("http-sbb", ServiceState.STOPPED);

        IspnQueueClient<Void> client = new IspnQueueClient<>("http-sbb", transport, 2_000L);
        ServiceUnavailableException ex = assertThrows(
                ServiceUnavailableException.class,
                () -> client.call(new SleeRequest("ping", new byte[0])));
        assertTrue(ex.getMessage().contains("STOPPED"));
        assertTrue(ex.getMessage().contains("http-sbb"));
    }

    @Test
    void callRefusesWhenPeerNeverPublished() {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "fail-hard-missing");
        clusterManager.start();
        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        // stateOf → STOPPED when no record

        IspnQueueClient<Void> client = new IspnQueueClient<>("http-sbb", transport, 2_000L);
        assertThrows(
                ServiceUnavailableException.class,
                () -> client.call(new SleeRequest("ping", new byte[0])));
    }

    @Test
    void callTimesOutWhenReadyButNoServer() {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "fail-hard-timeout");
        clusterManager.start();
        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.publishState("http-sbb", ServiceState.READY);
        // No IspnQueueServer — inbox will never be consumed

        IspnQueueClient<Void> client = new IspnQueueClient<>("http-sbb", transport, 200L);
        ServiceCallTimeoutException ex = assertThrows(
                ServiceCallTimeoutException.class,
                () -> client.call(new SleeRequest("ping", new byte[0])));
        assertTrue(ex.getMessage().contains("Timed out"));
    }

    @Test
    void notifyRefusesWhenPeerStopped() {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "fail-hard-notify");
        clusterManager.start();
        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.publishState("http-sbb", ServiceState.STOPPED);

        IspnQueueClient<Void> client = new IspnQueueClient<>("http-sbb", transport);
        assertThrows(
                ServiceUnavailableException.class,
                () -> client.notify(new SleeRequest("event", new byte[0])));
    }
}
