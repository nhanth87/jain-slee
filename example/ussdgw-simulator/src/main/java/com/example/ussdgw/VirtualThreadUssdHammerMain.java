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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HttpClient RA-style load generator — fires N concurrent USSD begins via
 * {@code POST /api/ussd/begin-callback} and measures end-to-end latency
 * from POST to callback receipt.
 *
 * <p>For each session, one virtual thread:
 * <ol>
 *   <li>Registers a {@link CountDownLatch} keyed by sessionId.</li>
 *   <li>Fires the begin POST (returns 202 immediately).</li>
 *   <li>Awaits the callback latch.</li>
 *   <li>Records end-to-end latency.</li>
 * </ol>
 *
 * <p>The callback receiver is an embedded {@link HttpServer} on a random
 * free port; one VT per request to handle the callbacks, isolated from
 * the main outbound pool so callback latency doesn't poison the next fire.
 */
public final class VirtualThreadUssdHammerMain {

    private static final Pattern SESSION_ID_P =
            Pattern.compile("\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        String msisdnPrefix = args.length > 1 ? args[1] : "251911";
        int total = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        long timeoutMs = args.length > 3 ? Long.parseLong(args[3]) : 30000;

        // 1) Boot embedded callback receiver.
        HttpServer callbackServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        ConcurrentHashMap<String, CountDownLatch> pending =
                new ConcurrentHashMap<>();
        AtomicLong received = new AtomicLong();
        callbackServer.createContext("/cb", new CallbackHandler(pending, received));
        callbackServer.setExecutor(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cb-", 0).factory()));
        callbackServer.start();
        int port = callbackServer.getAddress().getPort();
        String callbackUrl = "http://127.0.0.1:" + port + "/cb";

        System.out.printf(Locale.ROOT,
                "[hammer] baseUrl=%s callbackUrl=%s total=%d timeoutMs=%d%n",
                baseUrl, callbackUrl, total, timeoutMs);

        // 2) Outbound client — VT executor shared with callbacks (could split).
        ThreadFactory outboundTf = Thread.ofVirtual().name("hammer-out-", 0).factory();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newThreadPerTaskExecutor(outboundTf))
                .build();

        List<Sample> samples = Collections.synchronizedList(new ArrayList<>(total));
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long t0 = System.nanoTime();
        for (int i = 0; i < total; i++) {
            int idx = i;
            String msisdn = String.format("%s%09d", msisdnPrefix, idx);
            CountDownLatch latch = new CountDownLatch(1);
            String expectedSessionId = null;
            pending.put("pending-" + idx, latch);
            long reqStart = System.nanoTime();
            try {
                String body = "{\"msisdn\":\"" + msisdn
                        + "\",\"ussdString\":\"*123#\"}";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl
                                + "/api/ussd/begin-callback?callbackUrl="
                                + java.net.URLEncoder.encode(callbackUrl,
                                        StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = client.send(req,
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 202) {
                    failed.incrementAndGet();
                    samples.add(new Sample(idx, msisdn, -1, -1, false));
                    pending.remove("pending-" + idx);
                    latch.countDown();
                    continue;
                }
                expectedSessionId = extractSessionId(resp.body());
                pending.put(expectedSessionId, latch);
                pending.remove("pending-" + idx);
                issued.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
                samples.add(new Sample(idx, msisdn, -1, -1, false));
                pending.remove("pending-" + idx);
                latch.countDown();
                continue;
            }
            // The actual await happens in the callback handler — here we
            // hand the latch off and let the HttpServer thread count it
            // down. We *don't* await here so all N fires happen in
            // tight succession; the per-request wall time is recovered
            // by the callback handler thread that owns the latch.
            // The end-to-end latency is captured when the handler runs.
            final String sid = expectedSessionId;
            final long start = reqStart;
            // Park a tiny VT that records latency when the latch trips.
            Thread.ofVirtual().name("await-" + idx).start(() -> {
                try {
                    boolean done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    samples.add(new Sample(idx, msisdn,
                            elapsedMs, elapsedMs, done));
                    if (!done) {
                        failed.incrementAndGet();
                        pending.remove(sid);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for all latches.
        for (CountDownLatch latch : pending.values()) {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Wait for the await-VTs to drain a bit.
        Thread.sleep(500);
        long elapsedNs = System.nanoTime() - t0;

        callbackServer.stop(0);

        samples.sort((a, b) -> Long.compare(a.beginMs, b.beginMs));
        long ok = samples.stream().filter(s -> s.success).count();
        long ko = samples.size() - ok;
        long totalMs = elapsedNs / 1_000_000;
        double throughput = total * 1000.0 / totalMs;

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.printf(Locale.ROOT,
                "total=%d ok=%d failed=%d elapsedMs=%d throughput=%.1f req/s%n",
                samples.size(), ok, ko, totalMs, throughput);
        if (ok > 0) {
            System.out.printf(Locale.ROOT,
                    "latency p50=%.0fms p95=%.0fms p99=%.0fms max=%.0fms%n",
                    pct(samples, 0.50), pct(samples, 0.95),
                    pct(samples, 0.99), maxMs(samples));
        }

        System.out.println();
        System.out.println("sessionId,msisdn,callbackArrivedMs,endToEndMs,success");
        for (Sample s : samples) {
            System.out.printf(Locale.ROOT,
                    "%s,%s,%d,%d,%s%n", s.sessionId == null ? "-" : s.sessionId,
                    s.msisdn, s.callbackArrivedMs, s.beginMs,
                    s.success ? "OK" : "FAIL");
        }
    }

    private static String extractSessionId(String body) {
        Matcher m = SESSION_ID_P.matcher(body);
        if (!m.find()) throw new IllegalStateException(
                "sessionId not in: " + body);
        return m.group(1);
    }

    private static double pct(List<Sample> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.min(sorted.size() - 1,
                Math.floor(p * sorted.size()));
        return sorted.get(idx).beginMs;
    }

    private static double maxMs(List<Sample> sorted) {
        return sorted.isEmpty() ? 0
                : sorted.get(sorted.size() - 1).beginMs;
    }

    private static final class Sample {
        final int idx;
        final String msisdn;
        final long callbackArrivedMs;
        final long beginMs;
        final boolean success;
        volatile String sessionId;

        Sample(int idx, String msisdn, long beginMs, long callbackArrivedMs,
               boolean success) {
            this.idx = idx;
            this.msisdn = msisdn;
            this.beginMs = beginMs;
            this.callbackArrivedMs = callbackArrivedMs;
            this.success = success;
        }
    }

    private static final class CallbackHandler implements HttpHandler {
        private final ConcurrentHashMap<String, CountDownLatch> pending;
        private final AtomicLong received;

        CallbackHandler(ConcurrentHashMap<String, CountDownLatch> pending,
                        AtomicLong received) {
            this.pending = pending;
            this.received = received;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String sessionId = exchange.getRequestHeaders()
                        .getFirst("X-USSD-Session-Id");
                byte[] body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = in.readAllBytes();
                }
                received.incrementAndGet();
                if (sessionId != null) {
                    CountDownLatch latch = pending.remove(sessionId);
                    if (latch != null) latch.countDown();
                }
            } finally {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
        }
    }
}
