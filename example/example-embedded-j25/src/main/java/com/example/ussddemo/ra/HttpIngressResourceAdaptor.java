/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.ra;

import com.example.ussddemo.embedded.UssdSessionStore;
import com.example.ussddemo.events.HttpUssdBeginEvent;
import com.microjainslee.api.ActivityContextHandle;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;
import com.microjainslee.api.SimpleActivityContextHandle;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;

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
 * Embedded HTTP ingress RA. Listens on {@code com.sun.net.httpserver} and
 * fires {@link HttpUssdBeginEvent} into the SLEE via {@link SleeEndpointPort}.
 */
public final class HttpIngressResourceAdaptor implements ResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(HttpIngressResourceAdaptor.class);

    /**
     * Prepares the HTTP session (store + pooled {@code HttpServerSbb} entity)
     * before the RA fires the initial event.
     */
    public interface SessionPreparer {
        void prepare(String sessionId, String callbackUrl, ActivityContextInterface aci);
    }

    private ResourceAdaptorContext context;
    private HttpServer server;
    private UssdSessionStore sessionStore;
    private SessionPreparer sessionPreparer;
    private int port = 8082;

    public void setPort(int port) {
        this.port = port;
    }

    public void setSessionStore(UssdSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public void setSessionPreparer(SessionPreparer sessionPreparer) {
        this.sessionPreparer = sessionPreparer;
    }

    public int port() {
        return server != null && server.getAddress() != null
                ? server.getAddress().getPort()
                : port;
    }

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
        if (context != null) {
            context.setResourceAdaptor(this);
        }
    }

    @Override
    public void raConfigure() {
        LOG.info("HTTP ingress RA configured on port {}", port);
    }

    @Override
    public void raActive() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start HTTP ingress server on port " + port, e);
        }
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = Thread.ofVirtual().name("http-ingress-", n.getAndIncrement()).factory();
        server.setExecutor(Executors.newThreadPerTaskExecutor(tf));
        server.createContext("/health", new HealthHandler());
        server.createContext("/api/ussd/begin", new BeginHandler(false));
        server.createContext("/api/ussd/begin-callback", new BeginHandler(true));
        server.createContext("/api/ussd/sessions/", new SessionHandler());
        server.start();
        port = server.getAddress().getPort();
        LOG.info("HTTP ingress RA listening on http://127.0.0.1:{}", port);
    }

    @Override
    public void raStopping() {
        LOG.info("HTTP ingress RA stopping");
    }

    @Override
    public void raInactive() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Override
    public void raUnconfigure() {
        raInactive();
        context = null;
    }

    private void onHttpBegin(String body, String callbackUrl, boolean requireCallback,
                             HttpExchange exchange) throws IOException {
        if (requireCallback && (callbackUrl == null || callbackUrl.isEmpty())) {
            writeJson(exchange, 400, "{\"error\":\"callbackUrl is required\"}");
            return;
        }
        String msisdn = extractJsonString(body, "msisdn");
        String ussdString = extractJsonString(body, "ussdString");
        if (msisdn == null || msisdn.trim().isEmpty()) {
            writeJson(exchange, 400, "{\"error\":\"msisdn is required\"}");
            return;
        }
        if (ussdString == null || ussdString.trim().isEmpty()) {
            writeJson(exchange, 400, "{\"error\":\"ussdString is required\"}");
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        try {
            fireHttpBegin(sessionId, msisdn.trim(), ussdString.trim(), callbackUrl);
        } catch (RuntimeException e) {
            LOG.error("HTTP begin failed session={}", sessionId, e);
            writeJson(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            return;
        }

        String response = "{\"sessionId\":\"" + sessionId + "\",\"status\":\"PROCESSING\"}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (callbackUrl != null) {
            exchange.getResponseHeaders().add("Location",
                    callbackUrl + "?sessionId=" + sessionId);
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(202, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void fireHttpBegin(String sessionId, String msisdn, String ussdString,
                               String callbackUrl) {
        if (context == null || context.getSleeEndpointPort() == null) {
            throw new IllegalStateException("HTTP RA has no SleeEndpointPort");
        }
        MicroSleeContainer container = container();
        ActivityContextInterface aci = container.createActivityContext(sessionId);
        if (sessionPreparer != null) {
            sessionPreparer.prepare(sessionId, callbackUrl, aci);
        }
        ActivityContextHandle handle = new SimpleActivityContextHandle(sessionId);
        context.getSleeEndpointPort().fireEvent(handle,
                new HttpUssdBeginEvent(sessionId, msisdn, ussdString, callbackUrl));
    }

    private MicroSleeContainer container() {
        if (context instanceof RaBootstrapContextImpl) {
            // S5 — getContainer() now returns Object for kernel-package
            // decoupling (see MicroSleeContainer.registerResourceAdaptor).
            Object c = ((RaBootstrapContextImpl) context).getContainer();
            return c instanceof MicroSleeContainer mc ? mc : null;
        }
        return null;
    }

    private final class BeginHandler implements HttpHandler {
        private final boolean requireCallback;

        BeginHandler(boolean requireCallback) {
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
            onHttpBegin(body, callbackUrl, requireCallback, ex);
        }
    }

    private final class SessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                writeJson(ex, 405, "{\"error\":\"method-not-allowed\"}");
                return;
            }
            if (sessionStore == null) {
                writeJson(ex, 503, "{\"error\":\"session-store-unavailable\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String sessionId = path.substring(path.lastIndexOf('/') + 1);
            UssdSessionStore.SessionRecord rec = sessionStore.get(sessionId);
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

    private static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            writeJson(ex, 200, "{\"status\":\"ok\"}");
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeJson(HttpExchange ex, int status, String body) throws IOException {
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
            if (pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static String extractJsonString(String json, String field) {
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

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
