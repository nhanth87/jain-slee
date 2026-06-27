/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus;

import com.example.ussddemo.quarkus.bootstrap.UssdDemoBootstrap;
import com.example.ussddemo.quarkus.grpc.StubGrpcMenuClient;
import com.example.ussddemo.quarkus.ra.HttpIngressResourceAdaptor;
import com.example.ussddemo.quarkus.service.UssdCallbackDispatcher;
import com.example.ussddemo.quarkus.service.UssdSbbWiring;
import com.example.ussddemo.quarkus.service.UssdSessionStore;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test via HTTP RA (not Quarkus REST).
 */
class QuarkusUssdSmokeTest {

    private MicroSleeContainer container;
    private UssdSessionStore sessionStore;
    private UssdCallbackDispatcher callbackDispatcher;
    private UssdSbbWiring wiring;
    private UssdDemoBootstrap bootstrap;
    private int httpPort;

    @BeforeEach
    void setUp() throws Exception {
        try (java.net.ServerSocket probe =
                     new java.net.ServerSocket(0, 0, InetSocketAddress.createUnresolved("127.0.0.1", 0).getAddress())) {
            httpPort = probe.getLocalPort();
        }

        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64)
                .preferVirtualThreads(false)
                .sbbPoolMin(4)
                .sbbPoolMax(32)
                .sbbPerVirtualThread(false)
                .build();
        container = new MicroSleeContainer(cfg);

        sessionStore = new UssdSessionStore();
        callbackDispatcher = new UssdCallbackDispatcher();
        wiring = new UssdSbbWiring();
        bootstrap = new UssdDemoBootstrap();
        inject(bootstrap, "sessionStore", sessionStore);
        inject(bootstrap, "callbackDispatcher", callbackDispatcher);
        inject(bootstrap, "wiring", wiring);
        inject(bootstrap, "sessionTimeoutMs", 5_000L);

        bootstrap.startForTesting(container, httpPort, new StubGrpcMenuClient());
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            HttpIngressResourceAdaptor httpRa = bootstrap.httpRa();
            if (httpRa != null) {
                httpRa.raInactive();
                httpRa.raUnconfigure();
            }
        }
        if (callbackDispatcher != null) {
            try {
                Field ex = UssdCallbackDispatcher.class.getDeclaredField("executor");
                ex.setAccessible(true);
                ((ExecutorService) ex.get(callbackDispatcher)).shutdownNow();
            } catch (Exception ignored) {
            }
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void callbackFlowViaHttpRa() throws Exception {
        try (CallbackReceiver receiver = new CallbackReceiver()) {
            receiver.start();
            String callbackUrl = receiver.url();
            String appUrl = "http://127.0.0.1:" + httpPort + "/api/ussd/begin-callback"
                    + "?callbackUrl=" + java.net.URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);

            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(appUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"msisdn\":\"251911000001\",\"ussdString\":\"*123#\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(202, resp.statusCode(), "body=" + resp.body());
            String sessionId = extractJson(resp.body(), "sessionId");
            assertNotNull(sessionId);
            assertEquals("PROCESSING", extractJson(resp.body(), "status"));

            assertTrue(receiver.delivered.await(15, TimeUnit.SECONDS),
                    "callback was not delivered within 15s");
            assertEquals("COMPLETED", receiver.status.get());
            assertEquals(sessionId, receiver.sessionId.get());
            assertNotNull(receiver.responseText.get());
            assertTrue(receiver.responseText.get().length() > 0);
        }
    }

    @Test
    void pollingFlowViaHttpRa() throws Exception {
        String appUrl = "http://127.0.0.1:" + httpPort + "/api/ussd/begin";
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(appUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"msisdn\":\"251911000002\",\"ussdString\":\"*123#\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, resp.statusCode());
        String sessionId = extractJson(resp.body(), "sessionId");
        assertNotNull(sessionId);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String status = "PROCESSING";
        while (System.nanoTime() < deadline && "PROCESSING".equals(status)) {
            HttpResponse<String> poll = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort
                                    + "/api/ussd/sessions/" + sessionId))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, poll.statusCode());
            status = extractJson(poll.body(), "status");
            Thread.sleep(50L);
        }
        assertEquals("COMPLETED", status);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static String extractJson(String json, String field) {
        if (json == null) {
            return null;
        }
        String marker = "\"" + field + "\":\"";
        int s = json.indexOf(marker);
        if (s < 0) {
            return null;
        }
        int e = json.indexOf('"', s + marker.length());
        return e < 0 ? null : json.substring(s + marker.length(), e);
    }

    static final class CallbackReceiver implements AutoCloseable {
        final HttpServer server;
        final int port;
        final CountDownLatch delivered = new CountDownLatch(1);
        final AtomicReference<String> status = new AtomicReference<>();
        final AtomicReference<String> sessionId = new AtomicReference<>();
        final AtomicReference<String> responseText = new AtomicReference<>();

        CallbackReceiver() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.port = server.getAddress().getPort();
            this.server.createContext("/cb", new Handler());
            this.server.setExecutor(Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("rcv-q-", 0).factory()));
        }

        void start() {
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + port + "/cb";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private final class Handler implements HttpHandler {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] body = ex.getRequestBody().readAllBytes();
                String json = new String(body, StandardCharsets.UTF_8);
                status.set(extractJson(json, "status"));
                sessionId.set(extractJson(json, "sessionId"));
                responseText.set(extractJson(json, "responseText"));
                ex.sendResponseHeaders(204, -1);
                ex.close();
                delivered.countDown();
            }
        }
    }
}
