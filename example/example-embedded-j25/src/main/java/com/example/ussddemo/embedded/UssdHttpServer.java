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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDK-built-in HTTP front-end for the embedded demo. Exposes the same
 * HttpClient RA-style callback API as the Quarkus variant
 * ({@code POST /api/ussd/begin-callback?callbackUrl=...}) without
 * pulling in JAX-RS.
 */
public final class UssdHttpServer {

    private static final Logger LOG = LogManager.getLogger(UssdHttpServer.class);

    private final HttpServer server;
    private final UssdDemoRuntime runtime;
    private final int port;

    public UssdHttpServer(int port, UssdDemoRuntime runtime) throws IOException {
        this.port = port;
        this.runtime = runtime;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        // Virtual-thread executor so a slow caller never blocks the front-end.
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = Thread.ofVirtual()
                .name("http-", n.getAndIncrement())
                .factory();
        this.server.setExecutor(Executors.newThreadPerTaskExecutor(tf));

        this.server.createContext("/health", new HealthHandler());
        this.server.createContext("/api/ussd/begin", new BeginHandler(runtime, false));
        this.server.createContext("/api/ussd/begin-callback",
                new BeginHandler(runtime, true));
        this.server.createContext("/api/ussd/sessions/", new SessionHandler());
    }

    public void start() {
        server.start();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    public int port() {
        return port;
    }

    // --- Handlers ---------------------------------------------------------------

    private static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            writeJson(ex, 200, "{\"status\":\"ok\"}");
        }
    }

    /**
     * Unified handler for both {@code /api/ussd/begin} (polling) and
     * {@code /api/ussd/begin-callback} (HttpClient RA-style callback).
     * The {@code requireCallback} flag is the only difference.
     */
    private final class BeginHandler implements HttpHandler {
        private final UssdDemoRuntime runtime;
        private final boolean requireCallback;

        BeginHandler(UssdDemoRuntime runtime, boolean requireCallback) {
            this.runtime = runtime;
            this.requireCallback = requireCallback;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                writeJson(ex, 405, "{\"error\":\"method-not-allowed\"}");
                return;
            }
            String body = readBody(ex);
            String callbackUrl = queryParam(ex.getRequestURI(), "callbackUrl");
            if (requireCallback && (callbackUrl == null || callbackUrl.isEmpty())) {
                writeJson(ex, 400, "{\"error\":\"callbackUrl is required\"}");
                return;
            }
            beginAndRespond(ex, body, callbackUrl);
        }
    }

    private void beginAndRespond(HttpExchange ex, String body, String callbackUrl)
            throws IOException {
        String sessionId = UUID.randomUUID().toString();
        try {
            runtime.beginSession(sessionId, body, callbackUrl);
        } catch (RuntimeException e) {
            LOG.error("begin failed", e);
            writeJson(ex, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }
        String response = "{\"sessionId\":\"" + sessionId
                + "\",\"status\":\"PROCESSING\"}";
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (callbackUrl != null) {
            ex.getResponseHeaders().add("Location",
                    callbackUrl + "?sessionId=" + sessionId);
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(202, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private final class SessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                writeJson(ex, 405, "{\"error\":\"method-not-allowed\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String sessionId = path.substring(path.lastIndexOf('/') + 1);
            UssdSessionStore.SessionRecord rec = runtime.sessionStore().get(sessionId);
            if (rec == null) {
                writeJson(ex, 404, "{\"error\":\"unknown-session\"}");
                return;
            }
            StringBuilder sb = new StringBuilder(128);
            sb.append('{');
            sb.append("\"sessionId\":\"").append(escape(sessionId)).append("\",");
            sb.append("\"status\":\"").append(rec.getStatus().name()).append("\",");
            if (rec.getResponseText() != null) {
                sb.append("\"responseText\":\"").append(escape(rec.getResponseText()))
                        .append("\",");
            }
            if (rec.getErrorMessage() != null) {
                sb.append("\"errorMessage\":\"").append(escape(rec.getErrorMessage()))
                        .append("\",");
            }
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            sb.append('}');
            writeJson(ex, 200, sb.toString());
        }
    }

    // --- helpers ---------------------------------------------------------------

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeJson(HttpExchange ex, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String queryParam(URI uri, String name) {
        String q = uri.getRawQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if (key.equals(name)) {
                return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
