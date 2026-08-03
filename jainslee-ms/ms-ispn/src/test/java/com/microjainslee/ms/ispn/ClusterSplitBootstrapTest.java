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
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.annotation.SleeService;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.ServiceLifecycleHooks;
import com.microjainslee.ms.core.config.DeploymentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulates cluster topology in one JVM: signaling "hosted" via ISPN server,
 * app node starts only {@code app} and calls signaling remotely through the queue.
 */
class ClusterSplitBootstrapTest {

    @SleeService(name = "signaling")
    static final class SignalingMarker {}

    @SleeService(name = "app", dependsOn = {"signaling"})
    static final class AppMarker {}

    private ClusterManager clusterManager;
    private IspnQueueServer signalingServer;
    private MicrosleeBootstrap boot;

    @AfterEach
    void tearDown() {
        if (boot != null) {
            boot.stop();
        }
        if (signalingServer != null) {
            signalingServer.stop();
        }
        if (clusterManager != null) {
            clusterManager.stop();
        }
    }

    @Test
    void appNodeCallsRemoteSignalingViaIspn() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "node-2");
        clusterManager.start();
        IspnTransportManager transport = new IspnTransportManager(clusterManager);

        // Pretend node-1 already hosts signaling
        signalingServer = new IspnQueueServer("signaling", transport, req ->
                SleeResponse.ok("sig".getBytes(StandardCharsets.UTF_8)));
        signalingServer.start();
        transport.publishState("signaling", ServiceState.READY);

        DeploymentConfig cfg = DeploymentConfig.builder()
                .mode(DeploymentConfig.Mode.MICROSERVICES)
                .myNodeId("node-2")
                .node("node-1", "127.0.0.1", 9000)
                .node("node-2", "127.0.0.1", 9000)
                .service("signaling", "node-1")
                .service("app", "node-2")
                .build();

        ServiceLifecycleHooks appHooks = new IspnServiceLifecycleHooks(transport, desc ->
                req -> SleeResponse.ok(("app:" + req.operation()).getBytes(StandardCharsets.UTF_8)));

        boot = MicrosleeBootstrap.create(
                cfg,
                List.of(
                        SleeServiceDescriptor.fromAnnotation(SignalingMarker.class),
                        SleeServiceDescriptor.fromAnnotation(AppMarker.class)),
                appHooks,
                new IspnRemoteClientFactory(transport),
                transport);

        boot.start();

        assertEquals(ServiceState.READY, boot.orchestrator().localStates().get("app"));
        // signaling is remote — not started locally
        assertEquals(ServiceState.STOPPED, boot.orchestrator().localStates().get("signaling"));

        SleeResponse resp = boot.client("signaling")
                .call(new SleeRequest("ussd", new byte[0]));
        assertTrue(resp.success());
        assertEquals("sig", new String(resp.payload(), StandardCharsets.UTF_8));
    }
}
