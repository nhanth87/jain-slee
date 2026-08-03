/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus;

import com.example.ms.quarkus.services.HttpAuxService;
import com.example.ms.quarkus.services.HttpRaService;
import com.example.ms.quarkus.services.HttpSbbService;
import com.example.ms.quarkus.services.MsSharedDiagHandler;
import com.example.ms.quarkus.services.MsSharedDiagProvider;
import com.example.ms.quarkus.services.MsSharedStatusProvider;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.core.SleeServiceCatalog;
import com.microjainslee.ms.core.SleeServiceHandlerRegistry;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnQueueServer;
import com.microjainslee.ms.ispn.IspnTransportManager;
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
    private IspnQueueServer remotePeer;

    private static List<SleeServiceDescriptor> allDescriptors() {
        return SleeServiceCatalog.load();
    }

    private static SleeServiceHandlerRegistry nnRegistry() {
        return SleeServiceHandlerRegistry.discover(allDescriptors());
    }

    @BeforeEach
    void setUp() {
        HttpRaService.resetCalls();
        HttpAuxService.resetCalls();
        HttpSbbService.resetCalls();
        MsSharedStatusProvider.resetCalls();
        MsSharedDiagHandler.resetCalls();
        container = new MicroSleeContainer();
        container.start();
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.bootstrap().stop();
            runtime = null;
        }
        if (remotePeer != null) {
            remotePeer.stop();
            remotePeer = null;
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
                container, clusterManager, DeploymentConfig.singleNode());

        SleeResponse resp = runtime.bootstrap().client("http-ra")
                .call(new SleeRequest("ping", new byte[0]));
        assertTrue(resp.success());
        assertEquals("pong", new String(resp.payload(), StandardCharsets.UTF_8));
        assertEquals(1, HttpRaService.calls());
        assertTrue(runtime.config().isLocal("http-ra"));
        assertTrue(runtime.config().isLocal("http-aux"));
    }

    @Test
    void singleModeNnSharedHandlersAcrossServices() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "single");
        clusterManager.start();

        SleeServiceHandlerRegistry registry = nnRegistry();
        assertTrue(registry.describe().get("http-ra").stream()
                .anyMatch(s -> s.contains("MsSharedStatusProvider")));
        assertTrue(registry.describe().get("http-sbb").stream()
                .anyMatch(s -> s.contains("MsSharedDiagProvider")));

        runtime = MicrosleeMsSupport.start(
                container, clusterManager, DeploymentConfig.singleNode());

        SleeResponse statusRa = runtime.bootstrap().client("http-ra")
                .call(new SleeRequest("status", new byte[0]));
        SleeResponse statusSbb = runtime.bootstrap().client("http-sbb")
                .call(new SleeRequest("status", new byte[0]));
        SleeResponse diagAux = runtime.bootstrap().client("http-aux")
                .call(new SleeRequest("diag", new byte[0]));

        assertTrue(statusRa.success());
        assertEquals("shared-status:http-ra",
                new String(statusRa.payload(), StandardCharsets.UTF_8));
        assertEquals("shared-status:http-sbb",
                new String(statusSbb.payload(), StandardCharsets.UTF_8));
        assertEquals("shared-diag",
                new String(diagAux.payload(), StandardCharsets.UTF_8));
        assertTrue(MsSharedStatusProvider.calls() >= 2);
        assertEquals(1, MsSharedDiagProvider.calls());
    }

    @Test
    void microServicesRaNodeCallsRemoteHttpSbb() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "node-ra");
        clusterManager.start();

        remotePeer = new IspnQueueServer(
                "http-sbb",
                new IspnTransportManager(clusterManager),
                new HttpSbbService());
        remotePeer.start();
        new IspnTransportManager(clusterManager).publishState("http-sbb", ServiceState.READY);

        DeploymentConfig cfg = DeploymentConfig.builder()
                .mode(DeploymentConfig.Mode.MICROSERVICES)
                .myNodeId("node-ra")
                .node("node-ra", "127.0.0.1", 9000)
                .node("node-sbb", "127.0.0.1", 9000)
                .service("http-ra", "node-ra")
                .service("http-aux", "node-ra")
                .service("http-sbb", "node-sbb")
                .build();

        runtime = MicrosleeMsSupport.start(container, clusterManager, cfg);

        assertTrue(cfg.isLocal("http-ra"));
        assertTrue(cfg.isLocal("http-aux"));
        assertFalse(cfg.isLocal("http-sbb"));

        SleeResponse resp = runtime.bootstrap().client("http-sbb")
                .call(new SleeRequest("ping", new byte[0]));
        assertTrue(resp.success());
        assertEquals("http-sbb-handled:ping",
                new String(resp.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void microServicesRaNodeFailsWhenHttpSbbNotReady() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "node-ra");
        clusterManager.start();

        DeploymentConfig cfg = DeploymentConfig.builder()
                .mode(DeploymentConfig.Mode.MICROSERVICES)
                .myNodeId("node-ra")
                .node("node-ra", "127.0.0.1", 9000)
                .node("node-sbb", "127.0.0.1", 9000)
                .service("http-ra", "node-ra")
                .service("http-aux", "node-ra")
                .service("http-sbb", "node-sbb")
                .build();

        runtime = MicrosleeMsSupport.start(container, clusterManager, cfg);

        assertEquals(ServiceState.STOPPED, runtime.transport().stateOf("http-sbb"));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.microjainslee.ms.api.exception.ServiceUnavailableException.class,
                () -> runtime.bootstrap().client("http-sbb")
                        .call(new SleeRequest("ping", new byte[0])));
        assertTrue(ex.getMessage().contains("http-sbb"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no READY peer"), ex.getMessage());
        assertEquals(0, HttpSbbService.calls());
    }

    @Test
    void microServicesSbbNodeCallsRemoteHttpRa() throws Exception {
        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "node-sbb");
        clusterManager.start();

        remotePeer = new IspnQueueServer(
                "http-ra",
                new IspnTransportManager(clusterManager),
                new HttpRaService());
        remotePeer.start();
        new IspnTransportManager(clusterManager).publishState("http-ra", ServiceState.READY);

        DeploymentConfig cfg = DeploymentConfig.builder()
                .mode(DeploymentConfig.Mode.MICROSERVICES)
                .myNodeId("node-sbb")
                .node("node-ra", "127.0.0.1", 9000)
                .node("node-sbb", "127.0.0.1", 9000)
                .service("http-ra", "node-ra")
                .service("http-aux", "node-ra")
                .service("http-sbb", "node-sbb")
                .build();

        runtime = MicrosleeMsSupport.start(container, clusterManager, cfg);

        assertFalse(cfg.isLocal("http-ra"));
        assertTrue(cfg.isLocal("http-sbb"));

        SleeResponse resp = runtime.bootstrap().client("http-ra")
                .call(new SleeRequest("echo", "x".getBytes(StandardCharsets.UTF_8)));
        assertTrue(resp.success());
        assertEquals("echo:x", new String(resp.payload(), StandardCharsets.UTF_8));
    }
}
