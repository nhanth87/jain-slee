/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring;

import com.example.ussddemo.spring.grpc.MockGrpcMenuClient;
import com.example.ussddemo.spring.service.UssdCallbackDispatcher;
import com.example.ussddemo.spring.service.UssdDemoRuntime;
import com.example.ussddemo.spring.service.UssdSessionStore;
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
 * Wiring test for the Spring Boot variant of the USSD gateway demo.
 *
 * <p>This test does <b>not</b> use {@code @SpringBootTest} because
 * Spring Boot 3.3.0's bytecode reader cannot read the Java 25 (v69)
 * class files in {@code jainslee-core 1.1.0} (same Quarkus 3.15.1
 * caveat). Instead we exercise the production classes
 * ({@link UssdDemoRuntime}, {@link UssdSessionStore},
 * {@link UssdCallbackDispatcher}, {@link MockGrpcMenuClient})
 * directly through a {@link MicroSleeContainer} we construct by
 * hand -- proving the Spring wiring pattern works without ever
 * booting the Spring runtime.
 *
 * <p>The Spring runtime path is exercised separately via
 * {@code mvn spring-boot:run} once the project upgrades to a Spring
 * Boot version that supports Java 25 as a target.
 */
class SpringUssdSmokeTest {

    private MicroSleeContainer container;
    private UssdSessionStore sessionStore;
    private UssdCallbackDispatcher callbackDispatcher;
    private MockGrpcMenuClient grpcClient;
    private UssdDemoRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64)
                .preferVirtualThreads(false)
                .sbbPoolMin(4)
                .sbbPoolMax(32)
                .sbbPerVirtualThread(false)
                .build();
        container = new MicroSleeContainer(cfg);
        container.start();

        sessionStore = new UssdSessionStore();
        callbackDispatcher = new UssdCallbackDispatcher();
        grpcClient = new MockGrpcMenuClient(5L);

        runtime = buildRuntime();
    }

    @AfterEach
    void tearDown() {
        if (callbackDispatcher != null) {
            try {
                Field ex = UssdCallbackDispatcher.class.getDeclaredField("executor");
                ex.setAccessible(true);
                ((ExecutorService) ex.get(callbackDispatcher)).shutdownNow();
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void callbackFlowDeliversAsynchronously() throws Exception {
        try (CallbackReceiver receiver = new CallbackReceiver()) {
            receiver.start();
            String callbackUrl = receiver.url();
            String sessionId = "test-" + System.nanoTime();
            runtime.beginSession(sessionId, "251911000001", "*123#", callbackUrl);

            assertTrue(receiver.delivered.await(10, TimeUnit.SECONDS),
                    "callback was not delivered within 10s");
            assertEquals("COMPLETED", receiver.status.get());
            assertEquals(sessionId, receiver.sessionId.get());
            assertNotNull(receiver.responseText.get());
            assertTrue(receiver.responseText.get().length() > 0);
        }
    }

    @Test
    void pollingFlowReachesCompletedState() throws Exception {
        String sessionId = "test-" + System.nanoTime();
        runtime.beginSession(sessionId, "251911000002", "*123#", null);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        UssdSessionStore.SessionRecord rec = null;
        while (System.nanoTime() < deadline) {
            rec = sessionStore.get(sessionId);
            if (rec != null && rec.getStatus() == UssdSessionStore.Status.COMPLETED) {
                break;
            }
            Thread.sleep(50L);
        }
        assertNotNull(rec, "session not found in store");
        assertEquals(UssdSessionStore.Status.COMPLETED, rec.getStatus());
        assertNotNull(rec.getResponseText());
    }

    /**
     * Build a {@link UssdDemoRuntime} the same way the Spring container
     * would -- via @Autowired field injection. We replicate that here
     * with reflection.
     */
    private UssdDemoRuntime buildRuntime() throws Exception {
        UssdDemoRuntime r = new UssdDemoRuntime();
        Field fContainer = UssdDemoRuntime.class.getDeclaredField("container");
        fContainer.setAccessible(true);
        fContainer.set(r, container);

        Field fStore = UssdDemoRuntime.class.getDeclaredField("sessionStore");
        fStore.setAccessible(true);
        fStore.set(r, sessionStore);

        Field fDispatch = UssdDemoRuntime.class.getDeclaredField("callbackDispatcher");
        fDispatch.setAccessible(true);
        fDispatch.set(r, callbackDispatcher);

        Field fGrpc = UssdDemoRuntime.class.getDeclaredField("grpcClient");
        fGrpc.setAccessible(true);
        fGrpc.set(r, grpcClient);
        return r;
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
                    Thread.ofVirtual().name("rcv-s-", 0).factory()));
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
                status.set(extract(json, "status"));
                sessionId.set(extract(json, "sessionId"));
                responseText.set(extract(json, "responseText"));
                ex.sendResponseHeaders(204, -1);
                ex.close();
                delivered.countDown();
            }

            private String extract(String json, String field) {
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
        }
    }
}
