/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that exercises the HttpClient RA-style callback flow.
 *
 * <p>Boots an embedded {@link HttpServer} on a random free port to act as
 * the caller-supplied callback receiver, fires one
 * {@code POST /api/ussd/begin-callback?callbackUrl=...}, suspends on a
 * {@link CountDownLatch}, and asserts that the server POSTs the
 * {@code UssdSessionView} JSON envelope back once the SLEE pipeline
 * completes.
 *
 * <p>Net effect: <b>one outbound HTTP request from the caller, zero
 * polling, exactly the callback pattern documented in
 * {@code example/README.md}</b>.
 */
@QuarkusTest
class UssdDemoResourceTest {

    @Test
    void ussdBeginCallbackIsDeliveredAsynchronously() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> receivedStatus = new AtomicReference<>();
        AtomicReference<String> receivedSessionId = new AtomicReference<>();
        AtomicReference<String> receivedResponseText = new AtomicReference<>();

        receiver.createContext("/cb", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] body = ex.getRequestBody().readAllBytes();
                String json = new String(body, StandardCharsets.UTF_8);
                receivedStatus.set(extractJsonString(json, "status"));
                receivedSessionId.set(extractJsonString(json, "sessionId"));
                receivedResponseText.set(extractJsonString(json, "responseText"));
                ex.sendResponseHeaders(204, -1);
                ex.close();
                delivered.countDown();
            }
        });
        receiver.setExecutor(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("rcv-cb-", 0).factory()));
        receiver.start();
        try {
            int port = receiver.getAddress().getPort();
            String callbackUrl = "http://127.0.0.1:" + port + "/cb";

            // Fire the begin-callback. The server must return 202 Accepted
            // synchronously, BEFORE the SLEE pipeline finishes — the body
            // contains the freshly-minted sessionId.
            String sessionId = given()
                    .contentType(ContentType.JSON)
                    .body("{\"msisdn\":\"251911000001\",\"ussdString\":\"*123#\"}")
                    .when()
                    .post("/api/ussd/begin-callback?callbackUrl=" + callbackUrl)
                    .then()
                    .statusCode(202)
                    .header("Location", notNullValue())
                    .body("sessionId", notNullValue())
                    .body("status", equalTo("PROCESSING"))
                    .extract()
                    .path("sessionId");

            // Block until the callback receiver gets the result.
            assertTrue(delivered.await(10, TimeUnit.SECONDS),
                    "callback was not delivered within 10s");

            // No polling was performed. The callback body should mirror
            // what UssdSessionService would have returned synchronously
            // had the caller chosen GET /sessions/{id} — i.e. status
            // COMPLETED + responseText from the gRPC backend.
            assertEquals("COMPLETED", receivedStatus.get());
            assertEquals(sessionId, receivedSessionId.get());
            assertNotNull(receivedResponseText.get(),
                    "callback body must include responseText from gRPC backend");
            assertTrue(receivedResponseText.get().length() > 0,
                    "responseText should not be empty");
        } finally {
            receiver.stop(0);
        }
    }

    /**
     * Minimal string-field extractor for the JSON envelope produced by
     * {@code UssdCallbackDispatcher}. Avoids pulling in a JSON parser
     * dependency just for this single test.
     */
    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return null;
        }
        return json.substring(valueStart, valueEnd);
    }
}
