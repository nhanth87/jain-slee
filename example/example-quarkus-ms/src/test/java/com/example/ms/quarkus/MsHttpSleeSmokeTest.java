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
import com.example.ms.quarkus.sbbs.MsAppBridgeSbb;
import com.example.ms.quarkus.services.HttpAuxService;
import com.example.ms.quarkus.services.HttpRaService;
import com.example.ms.quarkus.services.HttpSbbService;
import com.example.ms.quarkus.services.MsSharedDiagHandler;
import com.example.ms.quarkus.services.MsSharedStatusProvider;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import com.microjainslee.quarkus.ms.MsHttpGatewaySbb;
import com.microjainslee.quarkus.ms.MsHttpIngressSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke through real {@code ra-http-server} → {@link MsHttpGatewaySbb}
 * → MS clients (n-n registry) — no Quarkus CDI.
 */
class MsHttpSleeSmokeTest {

    private MicroSleeContainer container;
    private ClusterManager clusterManager;
    private MicrosleeMsSupport.MsRuntime runtime;
    private MsHttpIngressSupport.IngressResult ingress;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        HttpRaService.resetCalls();
        HttpAuxService.resetCalls();
        HttpSbbService.resetCalls();
        MsSharedStatusProvider.resetCalls();
        MsSharedDiagHandler.resetCalls();
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
                container, clusterManager, DeploymentConfig.singleNode());
        holder.set(runtime);

        container.registerSbbType(MsAppBridgeSbb.class,
                () -> new MsAppBridgeSbb(holder));
        container.createIesDispatcher();
        container.mapEventToSbb(MsServiceCallEvent.class, "MsAppBridgeSbb");

        ingress = MsHttpIngressSupport.wire(
                container,
                DeploymentConfig.singleNode(),
                "http-ra",
                0,
                true,
                runtime,
                MsHttpGatewaySbb.class,
                rt -> new MsHttpGatewaySbb(holder.isReady() ? holder.get() : rt));
        // bind to loopback for the test (wireHttpRa uses 0.0.0.0)
        port = ingress.httpPort();
    }

    @AfterEach
    void tearDown() {
        if (ingress != null) {
            try {
                ingress.deactivateHttpRa();
            } catch (RuntimeException ignored) {
            }
        }
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
    void healthIsUpInSingleMode() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("\"status\":\"UP\""), resp.body());
        assertTrue(resp.body().contains("\"mode\":\"SINGLE\""), resp.body());
        assertTrue(resp.body().contains("\"http-ra\":true"), resp.body());
        assertTrue(resp.body().contains("\"http-aux\":true"), resp.body());
        assertTrue(resp.body().contains("\"http-sbb\":true"), resp.body());
    }

    @Test
    void callHttpRaPingViaSbbChain() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port
                                + "/api/demo/call-ra?op=ping"))
                        .header("Content-Type", "text/plain")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("\"success\":true"), resp.body());
        assertTrue(resp.body().contains("\"payload\":\"pong\""), resp.body());
        assertTrue(resp.body().contains("\"viaLocal\":true"), resp.body());
        assertEquals(1, HttpRaService.calls());
    }

    @Test
    void callSharedStatusAndHandlersEndpoint() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> handlers = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ms/handlers"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, handlers.statusCode(), handlers.body());
        assertTrue(handlers.body().contains("\"nn\":true"), handlers.body());
        assertTrue(handlers.body().contains("http-ra"), handlers.body());
        assertTrue(handlers.body().contains("http-aux"), handlers.body());

        HttpResponse<String> status = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port
                                + "/api/demo/call-sbb?op=status"))
                        .header("Content-Type", "text/plain")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, status.statusCode(), status.body());
        assertTrue(status.body().contains("shared-status:http-sbb"), status.body());
        assertTrue(MsSharedStatusProvider.calls() >= 1);

        HttpResponse<String> aux = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port
                                + "/api/demo/call-aux?op=diag"))
                        .header("Content-Type", "text/plain")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, aux.statusCode(), aux.body());
        assertTrue(aux.body().contains("shared-diag"), aux.body());
        assertEquals(1, MsSharedDiagHandler.calls());
    }

    @Test
    void genericMsCallPath() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port
                                + "/api/ms/http-ra?op=ping"))
                        .header("Content-Type", "text/plain")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("\"success\":true"), resp.body());
        assertTrue(resp.body().contains("\"payload\":\"pong\""), resp.body());
    }
}
