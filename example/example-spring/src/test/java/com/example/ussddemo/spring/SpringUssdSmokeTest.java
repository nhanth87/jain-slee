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

import com.example.ussddemo.spring.events.HttpUssdBeginEvent;
import com.example.ussddemo.spring.profile.UssdSubscriberProfile;
import com.example.ussddemo.spring.ra.GrpcMenuResourceAdaptor;
import com.example.ussddemo.spring.ra.HttpIngressResourceAdaptor;
import com.example.ussddemo.spring.sbbs.GrpcClientSbb;
import com.example.ussddemo.spring.sbbs.HttpServerSbb;
import com.example.ussddemo.spring.sbbs.Ss7UssdIngressSbb;
import com.example.ussddemo.spring.service.InMemoryGrpcMenuClient;
import com.example.ussddemo.spring.service.UssdCallbackDispatcher;
import com.example.ussddemo.spring.service.UssdSessionStore;
import com.example.ussddemo.spring.service.UssdWiring;
import com.microjainslee.api.InitialEventSelector;
import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.core.InMemoryProfileFacility;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;
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
 * End-to-end smoke test for the RA-based Spring variant.
 *
 * <p>Boots {@link MicroSleeContainer}, both RAs, and the SBB chain without
 * starting Spring Boot (Java 25 bytecode compatibility). Drives
 * {@code POST /api/ussd/begin-callback} on the HTTP ingress RA.
 */
class SpringUssdSmokeTest {

    private MicroSleeContainer container;
    private UssdSessionStore sessionStore;
    private UssdCallbackDispatcher callbackDispatcher;
    private UssdWiring wiring;
    private HttpIngressResourceAdaptor httpRa;
    private int httpPort;

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
        wiring = new UssdWiring();
        wiring.setContainer(container);

        container.setInitialEventSelectorCustomizer(this::customizeInitialEvent);
        seedProfiles();

        httpPort = findFreePort();
        httpRa = new HttpIngressResourceAdaptor();
        httpRa.setSessionStore(sessionStore);
        httpRa.setPort(httpPort);
        activateRa(httpRa, "http-ingress");
        wiring.setHttpRa(httpRa);

        GrpcMenuResourceAdaptor grpcRa = new GrpcMenuResourceAdaptor();
        grpcRa.setContainer(container);
        InMemoryGrpcMenuClient inMemory = new InMemoryGrpcMenuClient(5L);
        grpcRa.setGrpcMenuResolver(inMemory::resolveMenu);
        activateRa(grpcRa, "grpc-menu");
        wiring.setGrpcRa(grpcRa);
    }

    @AfterEach
    void tearDown() {
        if (httpRa != null) {
            httpRa.raStopping();
            httpRa.raInactive();
        }
        shutdownCallbackDispatcher();
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void callbackFlowDeliversAsynchronously() throws Exception {
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
            assertEquals(202, resp.statusCode());
            String sessionId = extractJson(resp.body(), "sessionId");
            assertNotNull(sessionId);

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

    private void customizeInitialEvent(InitialEventSelector selector) {
        if (selector.getEvent() instanceof HttpUssdBeginEvent) {
            String sessionId = ((HttpUssdBeginEvent) selector.getEvent()).getSessionId();
            String httpId = sessionId + "/HttpServer";
            HttpServerSbb httpSbb = new HttpServerSbb(wiring, sessionStore, callbackDispatcher);
            container.registerSbb(httpId, httpSbb);
            selector.setRootSbbId(httpId);
        }
    }

    private void activateRa(com.microjainslee.api.ResourceAdaptor ra, String entityName) {
        RaBootstrapContextImpl ctx = new RaBootstrapContextImpl(container, entityName);
        ctx.setResourceAdaptor(ra);
        ra.setResourceAdaptorContext(ctx);
        ra.raConfigure();
        ra.raActive();
    }

    private void seedProfiles() {
        InMemoryProfileFacility facility =
                (InMemoryProfileFacility) container.getProfileFacility();
        facility.createProfileTable("ussdSubscribers");
        seedSubscriber(facility, "251911000001", 2);
        seedSubscriber(facility, "251911000002", 1);
    }

    private static void seedSubscriber(InMemoryProfileFacility facility,
                                       String msisdn, int tier) {
        try {
            facility.createProfile("ussdSubscribers", msisdn, UssdSubscriberProfile.class);
            ProfileLocalObject plo = facility.getProfile(
                    new com.microjainslee.api.ProfileID("ussdSubscribers", msisdn));
            Profile profile = plo == null ? null : plo.getProfile();
            if (profile instanceof UssdSubscriberProfile) {
                UssdSubscriberProfile sub = (UssdSubscriberProfile) profile;
                sub.setMsisdn(msisdn);
                sub.setMenuTier(tier);
            }
        } catch (Exception ignored) {
        }
    }

    private static int findFreePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void shutdownCallbackDispatcher() {
        try {
            Field ex = UssdCallbackDispatcher.class.getDeclaredField("executor");
            ex.setAccessible(true);
            ((ExecutorService) ex.get(callbackDispatcher)).shutdownNow();
        } catch (Exception ignored) {
        }
    }

    private static String extractJson(String json, String field) {
        if (json == null) {
            return null;
        }
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        return valueEnd < 0 ? null : json.substring(valueStart, valueEnd);
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
