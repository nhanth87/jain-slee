/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.httpclient;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.RaHaSupport;
import com.microjainslee.cluster.RaStickyRouter;
import com.microjainslee.ra.httpclient.cluster.HttpStickyPost;
import com.microjainslee.ra.httpclient.collab.HttpClientSessionStore;
import com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent;
import com.microjainslee.ra.spi.AbstractResourceAdaptor;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbound HTTP callback RA on <b>Vert.x {@link WebClient}</b> — non-blocking
 * sends over a pooled, keep-alive connection manager, the same engine
 * {@code quarkus-rest-client-reactive} uses. Exposes the full Vert.x client
 * surface: connect/request timeouts, connection pool size, keep-alive,
 * redirect following and TLS trust.
 *
 * <p>Delivery policy: POST JSON, up to {@link #setMaxRetries(int)} retries with
 * exponential backoff (scheduled on the Vert.x event loop via
 * {@code setTimer}) for connect errors and 5xx responses. 4xx is a permanent
 * receiver error (no retry).</p>
 *
 * <p>On every terminal outcome the RA completes the
 * {@link HttpClientSessionStore} entry and fires an
 * {@link HttpCallbackCompletedEvent} so SBBs can react.</p>
 */
public final class HttpCallbackClientRa extends AbstractResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(HttpCallbackClientRa.class);

    private Vertx vertx;
    private WebClient webClient;
    private HttpClientSessionStore sessionStore;
    private RaBootstrapPort bootstrapPort;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile ClusterManager clusterManager;
    private volatile RaHaSupport haSupport;
    private volatile Object pendingCheckpointContainer;

    private int connectTimeoutMs = 10_000;
    private int requestTimeoutMs = 15_000;
    private int maxRetries = 2;
    private long retryBackoffMs = 500;
    /** Per-host WebClient pool; 20 starved multi-k TPS AS pull — Digicom lab default 256. */
    private int maxPoolSize = 256;
    private boolean keepAlive = true;
    private boolean followRedirects = true;
    private boolean trustAll = false;
    private int eventLoopThreads = 0;

    // -- configuration setters ------------------------------------------

    public void setConnectTimeoutMs(int ms) { this.connectTimeoutMs = ms; }
    public void setRequestTimeoutMs(int ms) { this.requestTimeoutMs = ms; }
    public void setMaxRetries(int n) { this.maxRetries = Math.max(0, n); }
    public void setRetryBackoffMs(long ms) { this.retryBackoffMs = ms; }
    public void setMaxPoolSize(int n) { this.maxPoolSize = Math.max(1, n); }

    // -- counters for status pages (LongAdder: no contention between event loops) ----------
    private final java.util.concurrent.atomic.LongAdder postsSent = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder answered = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder failed = new java.util.concurrent.atomic.LongAdder();

    /** POSTs accepted for sending (one per request, retries not counted again). */
    public long postsSent() { return postsSent.sum(); }
    /** Requests that got an HTTP answer below 500 (4xx included: the receiver answered). */
    public long answeredCount() { return answered.sum(); }
    /** Requests that ended without an answer: transport error, timeout, 5xx after retries. */
    public long failedCount() { return failed.sum(); }
    /** Requests sent and not finished yet. */
    public long inFlight() { return Math.max(0L, postsSent.sum() - answered.sum() - failed.sum()); }
    public int maxPoolSize() { return maxPoolSize; }
    public int requestTimeoutMs() { return requestTimeoutMs; }
    /** Vert.x event-loop threads for this entity; 0 keeps the Vert.x default. */
    public void setEventLoopThreads(int n) { this.eventLoopThreads = Math.max(0, n); }

    /** Forget a finished session: in-flight record and HA ownership (see EndSession). */
    public void endSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessionStore.remove(sessionId);
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.onClosed(sessionId);
        }
    }
    public void setKeepAlive(boolean keepAlive) { this.keepAlive = keepAlive; }
    public void setFollowRedirects(boolean follow) { this.followRedirects = follow; }
    /** Trust all TLS certificates (dev only). */
    public void setTrustAll(boolean trustAll) { this.trustAll = trustAll; }

    public void setSessionStore(HttpClientSessionStore store) { this.sessionStore = store; }
    public void setBootstrapPort(RaBootstrapPort port) { this.bootstrapPort = port; }

    public void setClusterManager(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    public void setMicroSleeContainer(Object container) {
        pendingCheckpointContainer = container;
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.checkpointBridge().bindContainer(container);
        }
    }

    public RaHaSupport haSupport() {
        return haSupport;
    }

    // -- lifecycle -------------------------------------------------------

    @Override
    public void raConfigure() {
        // Each RA entity owns its Vert.x instance. With several entities per node, give each a
        // share of the event loops instead of the Vert.x default of 2 x CPU cores apiece.
        vertx = eventLoopThreads > 0
                ? Vertx.vertx(new io.vertx.core.VertxOptions().setEventLoopPoolSize(eventLoopThreads))
                : Vertx.vertx();
        WebClientOptions options = new WebClientOptions()
                .setConnectTimeout(connectTimeoutMs)
                .setMaxPoolSize(maxPoolSize)
                .setKeepAlive(keepAlive)
                .setFollowRedirects(followRedirects)
                .setTrustAll(trustAll)
                .setVerifyHost(!trustAll)
                .setTcpNoDelay(true);
        webClient = WebClient.create(vertx, options);
        if (sessionStore == null) {
            sessionStore = new HttpClientSessionStore.InMemoryHttpClientSessionStore();
        }
        LOG.info("HTTP callback client RA configured (Vert.x WebClient, pool={}, retries={})",
                maxPoolSize, maxRetries);
    }

    @Override
    public void raActive() {
        ClusterManager cm = clusterManager;
        RaHaSupport ha = cm != null
                ? RaHaSupport.create(cm, "http-client")
                : RaHaSupport.localOnly("http-client", "local-http-client");
        Object pending = pendingCheckpointContainer;
        if (pending != null) {
            ha.checkpointBridge().bindContainer(pending);
        }
        if (ha.isClustered()) {
            ha.startStickyBus(env -> {
                if (env.payload() instanceof HttpStickyPost post) {
                    postBodyLocal(post.sessionId(), post.url(), post.body(), post.contentType(), post.headers());
                }
            });
        }
        haSupport = ha;
        active.set(true);
        LOG.info("HTTP callback client RA active");
    }

    @Override
    public void raStopping() {
        active.set(false);
        RaHaSupport ha = haSupport;
        haSupport = null;
        if (ha != null) {
            ha.stopStickyBus();
        }
        LOG.info("HTTP callback client RA stopping");
    }

    @Override
    public void raInactive() {
        // keep client until unconfigure — a stopping RA may still flush callbacks
    }

    @Override
    public void raUnconfigure() {
        active.set(false);
        if (webClient != null) {
            webClient.close();
            webClient = null;
        }
        if (vertx != null) {
            vertx.close();
            vertx = null;
        }
        super.raUnconfigure();
    }

    // -- send callback ---------------------------------------------------

    /**
     * Fire-and-forget JSON callback delivery with bounded retries.
     * Wraps {@code payload} in {@code {"sessionId","status","payload"}}.
     * Non-blocking — safe to call from SBB entity threads.
     */
    public void sendCallback(String sessionId, String callbackUrl, String payload) {
        String json = "{\"sessionId\":\"" + escapeJson(sessionId)
                + "\",\"status\":\"OK\",\"payload\":\"" + escapeJson(payload) + "\"}";
        postBody(sessionId, callbackUrl, json, "application/json", Map.of());
    }

    /**
     * HTTP request/response: POST raw {@code body} as {@code application/json}
     * and complete with status + response body ({@link HttpCallbackCompletedEvent}).
     * Use for AS pull — do not wrap in a callback envelope.
     */
    public void sendJsonPost(String sessionId, String url, String body) {
        sendJsonPost(sessionId, url, body, "application/json");
    }

    /**
     * HTTP request/response: POST raw {@code body} with the given
     * {@code Content-Type} and complete with status + response body
     * ({@link HttpCallbackCompletedEvent}). Body may be JSON or XML
     * (or any text payload). Null/blank {@code contentType} defaults to
     * {@code application/json}.
     */
    public void sendJsonPost(String sessionId, String url, String body, String contentType) {
        postBody(sessionId, url, body == null ? "" : body, resolveContentType(contentType), Map.of());
    }

    /**
     * Like {@link #sendJsonPost(String, String, String, String)} with extra request headers.
     * Response headers are returned on the completion event; nothing is remembered here.
     */
    public void sendPost(String sessionId, String url, String body, String contentType,
                         Map<String, String> headers) {
        postBody(sessionId, url, body == null ? "" : body, resolveContentType(contentType),
                headers == null ? Map.of() : headers);
    }

    private static String resolveContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/json" : contentType;
    }

    private void postBody(String sessionId, String url, String body, String contentType,
                          Map<String, String> headers) {
        RaHaSupport ha = haSupport;
        if (ha != null) {
            RaStickyRouter.Decision d = ha.decide(sessionId, true, active.get());
            if (d.action() == RaStickyRouter.Action.FORWARD_REMOTE && d.owner() != null) {
                ha.forward(d.owner().ownerNodeId(), sessionId,
                        new HttpStickyPost(sessionId, url, body, contentType, headers));
                return;
            }
            if (d.action() == RaStickyRouter.Action.REJECT) {
                LOG.warn("HTTP client RA sticky REJECT session={}: {}", sessionId, d.reason());
                return;
            }
        }
        postBodyLocal(sessionId, url, body, contentType, headers);
    }

    private void postBodyLocal(String sessionId, String url, String body, String contentType,
                               Map<String, String> headers) {
        if (url == null || url.isBlank()) {
            LOG.debug(() -> "HTTP client RA: no URL for session " + sessionId);
            return;
        }
        if (!active.get()) {
            LOG.warn("HTTP client RA not active — request for session {} dropped", sessionId);
            return;
        }
        WebClient client = this.webClient;
        if (client == null) {
            LOG.warn("HTTP client RA not configured — request for session {} dropped", sessionId);
            return;
        }
        postsSent.increment();   // before validation: an invalid URL ends as failed, not "never sent"
        try {
            URI.create(url); // fail fast on malformed URL
        } catch (IllegalArgumentException e) {
            LOG.warn("HTTP client RA: invalid URL '{}' for session {}", url, sessionId, e);
            completeWithError(sessionId, 0, "Invalid URL: " + e.getMessage());
            return;
        }

        sessionStore.track(sessionId, url);
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.onOpened(sessionId, "IN_FLIGHT", Map.of("url", url));
            ha.checkpointSbb(sessionId);
        }
        attemptSend(client, sessionId, url, body, contentType, headers, 0);
    }

    private void attemptSend(WebClient client, String sessionId, String callbackUrl,
                             String body, String contentType, Map<String, String> headers,
                             int attempt) {
        var request = client.postAbs(callbackUrl)
                .timeout(requestTimeoutMs)
                .putHeader("Content-Type", contentType);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey() != null && h.getValue() != null
                    && !"content-type".equalsIgnoreCase(h.getKey())) {
                request.putHeader(h.getKey(), h.getValue());
            }
        }
        request.sendBuffer(Buffer.buffer(body))
                .onSuccess(res -> {
                    int status = res.statusCode();
                    String responseBody = res.bodyAsString();
                    if (status < 500) {
                        if (status >= 400) {
                            LOG.warn("HTTP client RA: POST {} -> {} for session {} "
                                    + "(receiver error, not retried)",
                                    callbackUrl, status, sessionId);
                        } else {
                            LOG.debug("HTTP client RA: POST {} -> {} for session {}",
                                    callbackUrl, status, sessionId);
                        }
                        completeWithSuccess(sessionId, status, responseBody, headersOf(res.headers()));
                        return;
                    }
                    retryOrGiveUp(client, sessionId, callbackUrl, body, contentType, headers, attempt,
                            "HTTP " + status, status);
                })
                .onFailure(ex ->
                        retryOrGiveUp(client, sessionId, callbackUrl, body, contentType, headers, attempt,
                                ex.getMessage(), 0));
    }

    private void retryOrGiveUp(WebClient client, String sessionId, String callbackUrl,
                               String body, String contentType, Map<String, String> headers,
                               int attempt, String reason, int lastStatus) {
        if (attempt >= maxRetries || !active.get()) {
            LOG.warn("HTTP client RA: giving up after {} attempt(s) for session {} to {} ({})",
                    attempt + 1, sessionId, callbackUrl, reason);
            completeWithError(sessionId, lastStatus,
                    "Gave up after " + (attempt + 1) + " attempt(s): " + reason);
            return;
        }
        long delay = retryBackoffMs * (1L << attempt); // 500, 1000, 2000, ...
        LOG.info("HTTP client RA: retry {}/{} in {}ms for session {} ({})",
                attempt + 1, maxRetries, delay, sessionId, reason);
        Vertx v = this.vertx;
        if (v != null && active.get()) {
            v.setTimer(delay, id ->
                    attemptSend(client, sessionId, callbackUrl, body, contentType, headers, attempt + 1));
        }
    }

    // -- completion helpers ----------------------------------------------

    private void completeWithSuccess(String sessionId, int statusCode, String responseBody,
                                     Map<String, java.util.List<String>> responseHeaders) {
        sessionStore.complete(sessionId, statusCode, responseBody);
        answered.increment();
        touchHaComplete(sessionId, statusCode >= 400 ? "ERROR" : "OK", statusCode);
        fireCompletedEvent(sessionId, statusCode, responseBody, null, responseHeaders);
    }

    /** Vert.x MultiMap → lower-case name → values (arrival order). */
    static Map<String, java.util.List<String>> headersOf(io.vertx.core.MultiMap mm) {
        if (mm == null || mm.isEmpty()) {
            return Map.of();
        }
        Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : mm.entries()) {
            out.computeIfAbsent(e.getKey().toLowerCase(java.util.Locale.ROOT),
                    k -> new java.util.ArrayList<>(1)).add(e.getValue());
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private void completeWithError(String sessionId, int statusCode, String errorMessage) {
        sessionStore.complete(sessionId, statusCode, null);
        failed.increment();
        touchHaComplete(sessionId, "ERROR", statusCode);
        fireCompletedEvent(sessionId, statusCode, null, errorMessage, Map.of());
    }

    private void touchHaComplete(String sessionId, String status, int statusCode) {
        RaHaSupport ha = haSupport;
        if (ha == null) {
            return;
        }
        ha.onTouched(sessionId, status, Map.of("statusCode", String.valueOf(statusCode)));
        ha.checkpointSbb(sessionId);
    }

    private void fireCompletedEvent(String sessionId, int statusCode,
                                    String responseBody, String errorMessage,
                                    Map<String, java.util.List<String>> responseHeaders) {
        RaBootstrapPort bp = this.bootstrapPort;
        if (bp == null) {
            LOG.debug("No bootstrap port — skipping HttpCallbackCompletedEvent for session {}", sessionId);
            return;
        }
        HttpCallbackCompletedEvent event = new HttpCallbackCompletedEvent(
                sessionId, statusCode, responseBody, errorMessage, responseHeaders);
        // We are on a Vert.x event-loop thread here. Handing an event to the SLEE can block
        // (a new SBB entity is created, and the event queue applies back-pressure when it is
        // full). A blocked event loop stops every other HTTP request it serves, so the hand-off
        // runs on its own virtual thread and the event loop goes straight back to I/O.
        Thread.ofVirtual().name("http-client-deliver").start(() -> deliver(bp, sessionId, event));
    }

    /** Give the response to the SLEE, then end the activity that carried it. */
    private static void deliver(RaBootstrapPort bp, String sessionId, HttpCallbackCompletedEvent event) {
        try {
            ActivityHandle handle = bp.createActivityHandle(sessionId);
            bp.fireEvent(event, handle, null);
            // One request, one activity. Ending it (after the response event) lets the SLEE
            // free the SBB entity and the activity name that were created for this request.
            bp.endActivity(handle);
        } catch (RuntimeException e) {
            LOG.warn("Failed to deliver HttpCallbackCompletedEvent for session {}", sessionId, e);
        }
    }

    /** Minimal JSON string escaping — replaces backslash, double-quote, and control chars. */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
