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
import com.example.ms.quarkus.sbbs.MsGatewaySbb;
import com.example.ms.quarkus.services.HttpAuxService;
import com.example.ms.quarkus.services.HttpRaService;
import com.example.ms.quarkus.services.HttpSbbService;
import com.example.ms.quarkus.services.MsSharedDiagHandler;
import com.example.ms.quarkus.services.MsSharedStatusProvider;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.core.SleeServiceHandlerRegistry;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke through real {@code ra-http-server} → {@link MsGatewaySbb}
 * → MS clients (n-n registry) — no Quarkus CDI.
 */
class MsHttpSleeSmokeTest {

    private MicroSleeContainer container;
    private ClusterManager clusterManager;
    private MicrosleeMsSupport.MsRuntime runtime;
    private HttpServerRaEndpoint httpEndpoint;
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

        List<SleeServiceDescriptor> descriptors = List.of(
                SleeServiceDescriptor.fromAnnotation(HttpRaService.class),
                SleeServiceDescriptor.fromAnnotation(HttpAuxService.class),
                SleeServiceDescriptor.fromAnnotation(HttpSbbService.class));
        SleeServiceHandlerRegistry registry = SleeServiceHandlerRegistry.discover(descriptors);
        MsSharedDiagHandler diag = new MsSharedDiagHandler();
        for (String svc : List.of("http-ra", "http-sbb", "http-aux")) {
            registry.register(svc, List.of("diag"), 50, diag);
        }

        MsRuntimeHolder holder = new MsRuntimeHolder();
        runtime = MicrosleeMsSupport.start(
                container,
                clusterManager,
                DeploymentConfig.singleNode(),
                descriptors,
                registry);
        holder.set(runtime, registry.describe());

        container.registerSbbType(MsGatewaySbb.class,
                () -> new MsGatewaySbb(holder));
        container.registerSbbType(MsAppBridgeSbb.class,
                () -> new MsAppBridgeSbb(holder));
        container.createIesDispatcher();
        container.mapEventToSbb(HttpWebRequestEvent.class, "MsGatewaySbb");
        container.mapEventToSbb(MsServiceCallEvent.class, "MsAppBridgeSbb");

        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setPort(0);
        ra.setHost("127.0.0.1");
        httpEndpoint = new HttpServerRaEndpoint(ra);
        httpEndpoint.setPort(0);
        container.registerRa(httpEndpoint, httpEndpoint);
        port = httpEndpoint.port();
    }

    @AfterEach
    void tearDown() {
        if (httpEndpoint != null) {
            try {
                httpEndpoint.deactivate();
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
}
