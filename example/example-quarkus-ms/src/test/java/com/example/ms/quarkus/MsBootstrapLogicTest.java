/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus;

import com.example.ms.quarkus.handlers.ServiceHandlers;
import com.example.ms.quarkus.services.AppService;
import com.example.ms.quarkus.services.SignalingService;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnQueueServer;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsBootstrapLogicTest {

    private MicroSleeContainer container;
    private ClusterManager clusterManager;
    private MicrosleeMsSupport.MsRuntime runtime;
    private IspnQueueServer remoteSignaling;

    @BeforeEach
    void setUp() {
        ServiceHandlers.resetCounters();
        container = new MicroSleeContainer();
        container.start();
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.bootstrap().stop();
            runtime = null;
        }
        if (remoteSignaling != null) {
            remoteSignaling.stop();
            remoteSignaling = null;
        }
        if (clusterManager != null) {
            clusterManager.stop();
            clusterManager = null;
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void singleModeDirectCall() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "single");
        clusterManager.start();

        runtime = MicrosleeMsSupport.start(
                container,
                clusterManager,
                DeploymentConfig.singleNode(),
                List.of(
                        SleeServiceDescriptor.fromAnnotation(SignalingService.class),
                        SleeServiceDescriptor.fromAnnotation(AppService.class)),
                ServiceHandlers::forDescriptor);

        SleeResponse resp = runtime.bootstrap().client("signaling")
                .call(new SleeRequest("ping", new byte[0]));
        assertTrue(resp.success());
        assertEquals("pong", new String(resp.payload(), StandardCharsets.UTF_8));
        assertEquals(1, ServiceHandlers.signalingCalls());
        assertTrue(runtime.config().isLocal("signaling"));
    }

    @Test
    void clusterAppNodeCallsRemoteSignaling() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "node-app");
        clusterManager.start();

        // Pretend signaling is hosted elsewhere on the same cache manager
        remoteSignaling = new IspnQueueServer(
                "signaling",
                new com.microjainslee.ms.ispn.IspnTransportManager(clusterManager),
                ServiceHandlers.forDescriptor(
                        SleeServiceDescriptor.fromAnnotation(SignalingService.class)));
        remoteSignaling.start();
        new com.microjainslee.ms.ispn.IspnTransportManager(clusterManager)
                .publishState("signaling", ServiceState.READY);

        DeploymentConfig cfg = DeploymentConfig.builder()
                .mode(DeploymentConfig.Mode.CLUSTER)
                .myNodeId("node-app")
                .node("node-signaling", "127.0.0.1", 9000)
                .node("node-app", "127.0.0.1", 9000)
                .service("signaling", "node-signaling")
                .service("app", "node-app")
                .build();

        runtime = MicrosleeMsSupport.start(
                container,
                clusterManager,
                cfg,
                List.of(
                        SleeServiceDescriptor.fromAnnotation(SignalingService.class),
                        SleeServiceDescriptor.fromAnnotation(AppService.class)),
                ServiceHandlers::forDescriptor);

        assertFalse(cfg.isLocal("signaling"));
        assertTrue(cfg.isLocal("app"));

        SleeResponse resp = runtime.bootstrap().client("signaling")
                .call(new SleeRequest("echo", "x".getBytes(StandardCharsets.UTF_8)));
        assertTrue(resp.success());
        assertEquals("echo:x", new String(resp.payload(), StandardCharsets.UTF_8));
    }
}
