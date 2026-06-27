/*
 * micro-jainslee 1.1.0 — example application (ussdgw-simulator)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussdgw;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HttpClient RA-style simulator. Sibling of
 * {@link Ss7UssdSimulatorMain} (same callback flow, different log
 * prefix). This CLI:
 *
 * <ol>
 *   <li>Boots an embedded {@link HttpServer} on a random free port to act as
 *       the callback receiver.</li>
 *   <li>Issues {@code POST /api/ussd/begin-callback?callbackUrl=...} with the
 *       callback URL, which the server stores alongside the session.</li>
 *   <li>Suspends on a {@link CountDownLatch}; the embedded HttpServer handler
 *       counts the latch down when the server's async callback arrives.</li>
 *   <li>Prints the callback body (or times out after 30 s).</li>
 * </ol>
 *
 * <p>Net effect: 1 outbound HTTP request per session, zero polling, exactly
 * the same flow as Mobicents' {@code HttpClientSbb.execute()} callback.
 */
public final class HttpClientRaStyleMain {

    private static final Pattern SESSION_ID_P =
            Pattern.compile("\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private HttpClientRaStyleMain() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        String msisdn = args.length > 1 ? args[1] : "251911000001";
        String ussd = args.length > 2 ? args[2] : "*123#";

        HttpServer callbackServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        ConcurrentHashMap<String, CallbackPayload> callbacks =
                new ConcurrentHashMap<>();
        AtomicLong callbackSeq = new AtomicLong();
        callbackServer.createContext("/cb", new CallbackHandler(callbacks, callbackSeq));
        callbackServer.setExecutor(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cb-", 0).factory()));
        callbackServer.start();
        int localPort = callbackServer.getAddress().getPort();
        String callbackUrl = "http://127.0.0.1:" + localPort + "/cb";
        System.out.println("[RA-style] callback receiver listening on " + callbackUrl);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            String body = "{\"msisdn\":\"" + msisdn
                    + "\",\"ussdString\":\"" + ussd + "\"}";
            HttpRequest begin = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl
                            + "/api/ussd/begin-callback?callbackUrl="
                            + urlEncode(callbackUrl)))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            long t0 = System.nanoTime();
            System.out.println("[RA-style] firing MAP USSD begin msisdn="
                    + msisdn + " ussd=" + ussd);
            HttpResponse<String> beginResponse = client.send(begin,
                    HttpResponse.BodyHandlers.ofString());
            if (beginResponse.statusCode() != 202) {
                System.err.println("[RA-style] begin failed HTTP "
                        + beginResponse.statusCode() + ": " + beginResponse.body());
                System.exit(1);
            }
            String sessionId = extractSessionId(beginResponse.body());
            System.out.println("[RA-style] 202 Accepted session=" + sessionId
                    + " location=" + beginResponse.headers()
                            .firstValue("Location").orElse("?"));

            CallbackPayload payload = new CallbackPayload();
            callbacks.put(sessionId, payload);
            boolean received = payload.latch.await(30, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            if (!received) {
                System.err.println("[RA-style] TIMEOUT waiting for callback after 30s");
                System.exit(2);
            }
            System.out.println("[RA-style] callback received in " + elapsedMs + "ms");
            System.out.println("[RA-style] status: " + payload.status);
            if (payload.responseText != null) {
                System.out.println("[RA-style] MAP USSD response:");
                System.out.println(payload.responseText);
            }
        } finally {
            callbackServer.stop(0);
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String extractSessionId(String body) {
        Matcher m = SESSION_ID_P.matcher(body);
        if (!m.find()) throw new IllegalStateException(
                "sessionId not in: " + body);
        return m.group(1);
    }

    private static final class CallbackPayload {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String status;
        volatile String responseText;
        volatile String errorMessage;
    }

    private static final class CallbackHandler implements HttpHandler {
        private final ConcurrentHashMap<String, CallbackPayload> sink;
        private final AtomicLong seq;

        CallbackHandler(ConcurrentHashMap<String, CallbackPayload> sink,
                        AtomicLong seq) {
            this.sink = sink;
            this.seq = seq;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String sessionId = exchange.getRequestHeaders()
                    .getFirst("X-USSD-Session-Id");
            if (sessionId == null) {
                String q = exchange.getRequestURI().getQuery();
                if (q != null) {
                    for (String pair : q.split("&")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0 && "sessionId".equals(pair.substring(0, eq))) {
                            sessionId = pair.substring(eq + 1);
                        }
                    }
                }
            }
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            String text = new String(body, StandardCharsets.UTF_8);
            seq.incrementAndGet();
            System.out.printf(Locale.ROOT,
                    "[RA-style] <- callback #%d session=%s body=%s%n",
                    seq.get(), sessionId, text);
            CallbackPayload payload = sessionId == null ? null
                    : sink.remove(sessionId);
            if (payload != null) {
                payload.status = extractField(text, "status");
                payload.responseText = extractField(text, "responseText");
                payload.errorMessage = extractField(text, "errorMessage");
                payload.latch.countDown();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }

        private static String extractField(String json, String field) {
            Pattern p = Pattern.compile(
                    "\"" + field + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"",
                    Pattern.DOTALL);
            Matcher m = p.matcher(json);
            return m.find() ? m.group(1)
                    .replace("\\n", "\n").replace("\\\"", "\"") : null;
        }
    }
}
