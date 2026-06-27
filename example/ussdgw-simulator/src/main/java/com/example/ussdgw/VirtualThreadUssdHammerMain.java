/*
 * micro-jainslee 1.1.0 \u2014 load-test CLI built on Java 21+ virtual threads.
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussdgw;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concurrent load generator that exercises the USSD Quarkus demo with N
 * virtual threads firing {@code POST /api/ussd/begin} and then polling
 * {@code GET /api/ussd/sessions/{id}} in parallel.
 *
 * <p>This is the modern equivalent of the legacy
 * {@link Ss7UssdSimulatorMain} single-request flow: instead of blocking on
 * a sequential begin/poll pair, every request runs on its own virtual
 * thread and aggregates throughput / latency at the end.
 *
 * <p>CLI: {@code java -jar ussdgw-simulator.jar <baseUrl> <msisdn-prefix> <concurrency> <total> [poll-interval-ms]}
 *
 * <p>Output: CSV line per request on stdout (header on first line) plus a
 * summary block with p50/p95/p99/max latency and req/s.
 */
public final class VirtualThreadUssdHammerMain {

    private static final Pattern SESSION_ID = Pattern.compile(
            "\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern STATUS = Pattern.compile(
            "\\\"status\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private VirtualThreadUssdHammerMain() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        String msisdnPrefix = args.length > 1 ? args[1] : "251911";
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 64;
        int total = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
        long pollIntervalMs = args.length > 4 ? Long.parseLong(args[4]) : 25;
        long timeoutMs = args.length > 5 ? Long.parseLong(args[5]) : 30000;

        System.out.printf(Locale.ROOT,
                "[hammer] baseUrl=%s msisdnPrefix=%s concurrency=%d total=%d pollIntervalMs=%d%n",
                baseUrl, msisdnPrefix, concurrency, total, pollIntervalMs);

        // Virtual-thread-per-task executor: every in-flight request lives on
        // its own VT. Pinned=false so the carrier thread stays cheap.
        ThreadFactory vtFactory = Thread.ofVirtual().name("hammer-", 0).factory();
        var vtExecutor = Executors.newThreadPerTaskExecutor(vtFactory);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(vtExecutor)
                .build();

        List<CompletableFuture<Sample>> futures = new ArrayList<>(total);
        AtomicInteger issued = new AtomicInteger();
        long t0 = System.nanoTime();
        for (int i = 0; i < total; i++) {
            int idx = i;
            String msisdn = String.format("%s%09d", msisdnPrefix, idx);
            // Stagger slightly so we don't blow the heap at t=0
            if (i % concurrency == 0 && i > 0) {
                Thread.sleep(1);
            }
            futures.add(launch(client, baseUrl, msisdn, idx, pollIntervalMs, timeoutMs));
            issued.incrementAndGet();
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long elapsedNs = System.nanoTime() - t0;

        // Aggregate
        List<Sample> samples = new ArrayList<>(futures.size());
        for (CompletableFuture<Sample> f : futures) {
            samples.add(f.get());
        }
        samples.sort((a, b) -> Long.compare(a.endToEndMs, b.endToEndMs));

        long success = samples.stream().filter(s -> s.outcome == Outcome.OK).count();
        long beginFailed = samples.stream().filter(s -> s.outcome == Outcome.BEGIN_FAILED).count();
        long pollFailed = samples.stream().filter(s -> s.outcome == Outcome.POLL_FAILED).count();
        long timedOut = samples.stream().filter(s -> s.outcome == Outcome.TIMEOUT).count();

        System.out.println("sessionId,msisdn,beginMs,completeMs,endToEndMs,outcome,responseText");
        for (Sample s : samples) {
            System.out.printf(Locale.ROOT,
                    "%s,%s,%d,%d,%d,%s,%s%n",
                    s.sessionId == null ? "-" : s.sessionId,
                    s.msisdn, s.beginMs, s.completeMs, s.endToEndMs, s.outcome,
                    s.responseText == null ? "" : s.responseText.replace(',', ';').replace('\n', ' '));
        }

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.printf(Locale.ROOT, "total=%d success=%d beginFailed=%d pollFailed=%d timedOut=%d%n",
                total, success, beginFailed, pollFailed, timedOut);
        System.out.printf(Locale.ROOT, "elapsed=%.2fs throughput=%.1f req/s%n",
                elapsedNs / 1e9, total * 1e9 / elapsedNs);
        System.out.printf(Locale.ROOT, "latency p50=%.0fms p95=%.0fms p99=%.0fms max=%.0fms%n",
                pct(samples, 0.50), pct(samples, 0.95), pct(samples, 0.99), maxMs(samples));

        vtExecutor.shutdown();
    }

    /**
     * Issue one begin, then poll until COMPLETED / FAILED / timeout. Each
     * request runs on its own virtual thread (via the shared VT executor
     * on the {@link HttpClient}).
     */
    private static CompletableFuture<Sample> launch(HttpClient client, String baseUrl,
                                                    String msisdn, int idx, long pollMs,
                                                    long timeoutMs) {
        long reqStart = System.nanoTime();
        Sample s = new Sample();
        s.msisdn = msisdn;
        return begin(client, baseUrl, msisdn, reqStart)
                .thenCompose(beginResp -> {
                    long beginMs = (System.nanoTime() - reqStart) / 1_000_000;
                    s.beginMs = beginMs;
                    if (beginResp.statusCode() != 202) {
                        s.outcome = Outcome.BEGIN_FAILED;
                        s.completeMs = beginMs;
                        s.endToEndMs = beginMs;
                        return CompletableFuture.completedFuture(s);
                    }
                    s.sessionId = extract(SESSION_ID, beginResp.body(), 1);
                    return pollUntilDone(client, baseUrl, s, reqStart, pollMs, timeoutMs);
                });
    }

    private static CompletableFuture<HttpResponse<String>> begin(HttpClient client,
                                                                 String baseUrl, String msisdn,
                                                                 long reqStart) {
        String body = "{\"msisdn\":\"" + msisdn + "\",\"ussdString\":\"*123#\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ussd/begin"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    }

    private static CompletableFuture<Sample> pollUntilDone(HttpClient client, String baseUrl,
                                                           Sample s, long reqStart,
                                                           long pollMs, long timeoutMs) {
        long deadlineNanos = reqStart + timeoutMs * 1_000_000L;
        return pollOnce(client, baseUrl, s)
                .thenCompose(pollResp -> {
                    if (pollResp.statusCode() != 200) {
                        s.outcome = Outcome.POLL_FAILED;
                        s.completeMs = elapsedMs(reqStart);
                        s.endToEndMs = s.completeMs;
                        return CompletableFuture.completedFuture(s);
                    }
                    String status = extract(STATUS, pollResp.body(), 1);
                    if ("COMPLETED".equals(status)) {
                        s.outcome = Outcome.OK;
                        s.completeMs = elapsedMs(reqStart);
                        s.endToEndMs = s.completeMs;
                        s.responseText = extract(pollResp.body(), "responseText");
                        return CompletableFuture.completedFuture(s);
                    }
                    if ("FAILED".equals(status)) {
                        s.outcome = Outcome.POLL_FAILED;
                        s.completeMs = elapsedMs(reqStart);
                        s.endToEndMs = s.completeMs;
                        return CompletableFuture.completedFuture(s);
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        s.outcome = Outcome.TIMEOUT;
                        s.completeMs = elapsedMs(reqStart);
                        s.endToEndMs = s.completeMs;
                        return CompletableFuture.completedFuture(s);
                    }
                    // still PROCESSING -> wait then retry. The retry is
                    // scheduled on the shared VT executor so it doesn't
                    // block a carrier thread.
                    return CompletableFuture.runAsync(() -> {
                        try { Thread.sleep(pollMs); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }).thenCompose(v -> pollUntilDone(client, baseUrl, s, reqStart, pollMs, timeoutMs));
                });
    }

    private static CompletableFuture<HttpResponse<String>> pollOnce(HttpClient client,
                                                                    String baseUrl, Sample s) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ussd/sessions/" + s.sessionId))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String extract(Pattern p, String body, int group) {
        Matcher m = p.matcher(body);
        if (!m.find()) {
            return null;
        }
        return m.group(group);
    }

    /** Best-effort extraction of "responseText":"..." (used after status=COMPLETED). */
    private static String extract(String body, String field) {
        Pattern p = Pattern.compile(
                "\"" + field + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"", Pattern.DOTALL);
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1).replace("\\n", "\n").replace("\\\"", "\"") : null;
    }

    private static double pct(List<Sample> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.min(sorted.size() - 1, Math.floor(p * sorted.size()));
        return sorted.get(idx).endToEndMs;
    }

    private static double maxMs(List<Sample> sorted) {
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1).endToEndMs;
    }

    private enum Outcome { OK, BEGIN_FAILED, POLL_FAILED, TIMEOUT }

    private static final class Sample {
        String msisdn;
        String sessionId;
        String responseText;
        long beginMs;
        long completeMs;
        long endToEndMs;
        Outcome outcome = Outcome.OK;
    }
}
