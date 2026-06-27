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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HttpClient RA-style async callback dispatcher. Plain Java, no CDI.
 * Runs on a virtual-thread-per-task executor so a slow callback target
 * never blocks the SLEE event router.
 */
public final class UssdCallbackDispatcher {

    private static final Logger LOG = LogManager.getLogger(UssdCallbackDispatcher.class);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ussd-callback-", 0).factory());

    /** Asynchronous POST. Returns immediately; the actual HTTP call runs on a VT. */
    public void dispatch(String callbackUrl, String sessionId, String status,
                         String responseText, String errorMessage) {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return;
        }
        executor.submit(() -> post(callbackUrl, sessionId, status, responseText, errorMessage));
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void post(String callbackUrl, String sessionId, String status,
                      String responseText, String errorMessage) {
        StringBuilder body = new StringBuilder(128);
        body.append('{');
        body.append("\"sessionId\":\"").append(escape(sessionId)).append("\",");
        body.append("\"status\":\"").append(escape(status)).append("\",");
        if (responseText != null) {
            body.append("\"responseText\":\"").append(escape(responseText)).append("\",");
        }
        if (errorMessage != null) {
            body.append("\"errorMessage\":\"").append(escape(errorMessage)).append("\",");
        }
        if (body.charAt(body.length() - 1) == ',') {
            body.setLength(body.length() - 1);
        }
        body.append('}');
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(callbackUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-USSD-Session-Id", sessionId == null ? "" : sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                LOG.warn("[callback] {} session={} -> HTTP {}",
                        callbackUrl, sessionId, resp.statusCode());
            } else {
                LOG.debug("[callback] {} session={} -> HTTP {}",
                        callbackUrl, sessionId, resp.statusCode());
            }
        } catch (Exception e) {
            LOG.warn("[callback] failed to deliver session={} to {}", sessionId, callbackUrl);
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
