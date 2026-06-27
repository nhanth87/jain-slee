/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.embedded;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end smoke test for the embedded variant.
 *
 * <p>The test starts the embedded HTTP server on a random free port in
 * a background thread (the way a real deployment would), then drives
 * the HttpClient RA-style callback flow:
 *
 * <ol>
 *   <li>POST /api/ussd/begin-callback with a callbackUrl pointing at an
 *       embedded receiver HttpServer -- server returns 202 with
 *       Location header and a PROCESSING sessionId.</li>
 *   <li>The SLEE pipeline runs the Ss7UssdIngressSbb then the
 *       GrpcBackendSbb, both asynchronously on virtual threads.</li>
 *   <li>The runtime fires the callback POST back to our receiver with
 *       status COMPLETED + responseText from the mock gRPC backend.</li>
 *   <li>The receiver counts down a latch; the test asserts the body.</li>
 * </ol>
 *
 * <p>Run with: {@code mvn -B -ntp test}.
 */
public class EmbeddedUssdSmokeTest {

    private EmbeddedUssdMainRunner runner;
    private HttpServer receiver;
    private int receiverPort;

    @Before
    public void setUp() throws Exception {
        // Embedded receiver HttpServer -- the "caller" target.
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiverPort = receiver.getAddress().getPort();
        receiver.setExecutor(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("rcv-", 0).factory()));
        receiver.start();

        // The embedded app -- run on a random free port.
        runner = new EmbeddedUssdMainRunner(0); // 0 = pick random free port
        runner.start();
    }

    @After
    public void tearDown() {
        if (receiver != null) {
            receiver.stop(0);
        }
        if (runner != null) {
            runner.stop();
        }
    }

    @Test
    public void callbackIsDeliveredAsynchronously() throws Exception {
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

        String callbackUrl = "http://127.0.0.1:" + receiverPort + "/cb";
        String appUrl = "http://127.0.0.1:" + runner.port() + "/api/ussd/begin-callback"
                + "?callbackUrl=" + java.net.URLEncoder.encode(callbackUrl,
                        StandardCharsets.UTF_8);

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(appUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"msisdn\":\"251911000001\",\"ussdString\":\"*123#\"}"))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 202) {
            throw new AssertionError("POST /begin-callback expected 202 but got "
                    + resp.statusCode() + ", body=" + resp.body()
                    + ", requestUrl=" + appUrl);
        }
        String body = resp.body();
        String sessionId = extractJsonString(body, "sessionId");
        assertNotNull("202 response body must include sessionId", sessionId);
        assertEquals("PROCESSING", extractJsonString(body, "status"));

        // Block until the callback receiver gets the result.
        assertTrue("callback was not delivered within 10s",
                delivered.await(10, TimeUnit.SECONDS));

        assertEquals("COMPLETED", receivedStatus.get());
        assertEquals(sessionId, receivedSessionId.get());
        assertNotNull("callback body must include responseText from gRPC backend",
                receivedResponseText.get());
        assertTrue("responseText should not be empty",
                receivedResponseText.get().length() > 0);
    }

    @Test
    public void pollingPathStillWorks() throws Exception {
        // Same scenario but with no callbackUrl -- caller polls
        // /api/ussd/sessions/{id} instead.
        String appUrl = "http://127.0.0.1:" + runner.port() + "/api/ussd/begin";
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
        String sessionId = extractJsonString(resp.body(), "sessionId");
        assertNotNull(sessionId);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String status = "PROCESSING";
        while (System.nanoTime() < deadline && "PROCESSING".equals(status)) {
            HttpResponse<String> poll = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + runner.port()
                                    + "/api/ussd/sessions/" + sessionId))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, poll.statusCode());
            status = extractJsonString(poll.body(), "status");
            Thread.sleep(50L);
        }
        assertEquals("COMPLETED", status);
    }

    // --- helpers ---------------------------------------------------------------

    private static String extractJsonString(String json, String field) {
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
        if (valueEnd < 0) {
            return null;
        }
        return json.substring(valueStart, valueEnd);
    }

    /**
     * Boots {@link EmbeddedUssdMain} on a free port in a background thread.
     * The class-under-test binds to whatever port the OS picks, then we
     * read it back via a marker file.
     */
    private static final class EmbeddedUssdMainRunner {
        private final int requestedPort;
        private Thread thread;
        private Path portFile;
        private volatile int boundPort = -1;

        EmbeddedUssdMainRunner(int requestedPort) {
            this.requestedPort = requestedPort;
        }

        void start() throws Exception {
            portFile = Files.createTempFile("embedded-port-", ".txt");
            // Hint to EmbeddedUssdMain: write the port to this file.
            System.setProperty("microjainslee.port-marker", portFile.toString());
            // Run EmbeddedUssdMain with a free-port probe: ask the OS for a
            // port, write it to portFile, then call EmbeddedUssdMain.main
            // with that port.
            thread = new Thread(() -> {
                try (java.net.ServerSocket probe =
                        new java.net.ServerSocket(0, 0,
                                new InetSocketAddress("127.0.0.1", 0)
                                        .getAddress())) {
                    int free = probe.getLocalPort();
                    probe.close();
                    Files.writeString(portFile, Integer.toString(free));
                    boundPort = free;
                    EmbeddedUssdMain.main(new String[]{Integer.toString(free)});
                } catch (Exception e) {
                    throw new RuntimeException("EmbeddedUssdMain runner failed", e);
                }
            }, "embedded-main-runner");
            thread.setDaemon(true);
            thread.start();
            // Wait until the embedded HTTP server is up (poll /health).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            HttpClient probe = HttpClient.newHttpClient();
            while (System.nanoTime() < deadline) {
                try {
                    HttpResponse<String> r = probe.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("http://127.0.0.1:" + boundPort
                                            + "/health"))
                                    .GET()
                                    .timeout(java.time.Duration.ofMillis(500))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() == 200 && "{\"status\":\"ok\"}".equals(r.body())) {
                        return;
                    }
                } catch (Exception ignored) {
                    // not yet ready
                }
                Thread.sleep(50L);
            }
            throw new IllegalStateException("EmbeddedUssdMain did not come up in 10s");
        }

        int port() {
            return boundPort;
        }

        void stop() {
            // Trigger EmbeddedUssdMain's shutdown hook by interrupting the
            // runner thread. The hook calls httpServer.stop() and
            // container.stop().
            thread.interrupt();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                Files.deleteIfExists(portFile);
            } catch (IOException ignored) {
            }
        }
    }
}
