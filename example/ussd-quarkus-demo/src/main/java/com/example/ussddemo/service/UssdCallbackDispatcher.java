/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.service;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HttpClient RA-style callback dispatcher: when a USSD session completes, the
 * runtime fires an asynchronous HTTP POST back to the caller-supplied URL —
 * exactly the same flow as Mobicents' {@code HttpClientSbb.execute()} callback
 * but with the polarities inverted (server pushes to client rather than the
 * other way round).
 *
 * <p>The actual delivery runs on a dedicated virtual-thread-per-task executor
 * so a slow callback target never blocks the SLEE event router.
 *
 * <p>Failure to reach the callback URL is logged but does NOT mark the
 * session as failed — the session is already done server-side; the caller
 * is expected to have its own timeout / retry.
 */
@ApplicationScoped
public final class UssdCallbackDispatcher {

    private static final Logger LOG = Logger.getLogger(UssdCallbackDispatcher.class);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ussd-callback-", 0).factory());

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Schedule an asynchronous HTTP POST to {@code callbackUrl}. The body is a
     * JSON envelope mirroring {@link com.example.ussddemo.rest.UssdSessionView}.
     */
    public void dispatch(String callbackUrl, String sessionId, String status,
                         String responseText, String errorMessage) {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return;
        }
        executor.submit(() -> post(callbackUrl, sessionId, status, responseText, errorMessage));
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
                LOG.warnf("[callback] %s session=%s -> HTTP %d", callbackUrl, sessionId,
                        resp.statusCode());
            } else {
                LOG.debugf("[callback] %s session=%s -> HTTP %d", callbackUrl, sessionId,
                        resp.statusCode());
            }
        } catch (Exception e) {
            LOG.warnf(e, "[callback] failed to deliver session=%s to %s", sessionId, callbackUrl);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}