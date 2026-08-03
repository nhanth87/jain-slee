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

import com.example.ms.quarkus.bootstrap.MsRuntimeHolder;
import com.example.ms.quarkus.events.MsServiceCallEvent;
import com.example.ms.quarkus.handlers.ServiceHandlers;
import com.example.ms.quarkus.sbbs.MsAppBridgeSbb;
import com.example.ms.quarkus.services.HttpRaService;
import com.example.ms.quarkus.services.HttpSbbService;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivers {@link MsServiceCallEvent} on its own activity so
 * {@link MsAppBridgeSbb} is proven on the SLEE plane.
 */
class MsAppBridgeSbbTest {

    private MicroSleeContainer container;
    private ClusterManager clusterManager;
    private MicrosleeMsSupport.MsRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        ServiceHandlers.resetCounters();
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64)
                .preferVirtualThreads(false)
                .sbbPoolMin(4)
                .sbbPoolMax(64)
                .sbbPerVirtualThread(false)
                .build());
        container.start();

        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "single");
        clusterManager.start();

        MsRuntimeHolder holder = new MsRuntimeHolder();
        runtime = MicrosleeMsSupport.start(
                container,
                clusterManager,
                DeploymentConfig.singleNode(),
                List.of(
                        SleeServiceDescriptor.fromAnnotation(HttpRaService.class),
                        SleeServiceDescriptor.fromAnnotation(HttpSbbService.class)),
                ServiceHandlers::forDescriptor);
        holder.set(runtime);

        container.registerSbbType(MsAppBridgeSbb.class, () -> new MsAppBridgeSbb(holder));
        container.createIesDispatcher();
        container.mapEventToSbb(MsServiceCallEvent.class, "MsAppBridgeSbb");
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.bootstrap().stop();
        }
        if (clusterManager != null) {
            clusterManager.stop();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void bridgeCallHttpRaViaLocalEvent() throws Exception {
        MsServiceCallEvent call = new MsServiceCallEvent(
                "echo", "hi".getBytes(StandardCharsets.UTF_8), false);
        container.routeEvent(call, container.createActivityContext("bridge-test"));

        SleeResponse resp = call.response().get(5, TimeUnit.SECONDS);
        assertTrue(resp.success());
        assertEquals("echo:hi", new String(resp.payload(), StandardCharsets.UTF_8));
        assertEquals(1, ServiceHandlers.httpRaCalls());
    }
}
