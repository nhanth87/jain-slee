/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http2;

import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.ra.sbi.http2.admin.SbiHttp2AdminBindings;
import com.microjainslee.ra.sbi.http2.command.SbiOutboundCommand;
import com.microjainslee.ra.sbi.http2.events.SbiOperationEvent;
import com.microjainslee.ra.sbi.http2.events.SbiOutboundCompletedEvent;
import com.microjainslee.ra.sbi.http2.resilience.SbiResiliencePolicy;
import com.microjainslee.ra.sbi.http2.resilience.SbiSagaCoordinator;
import com.microjainslee.ra.sbi.openapi.SbiHttpVersion;
import com.microjainslee.ra.sbi.openapi.SbiOpenApiCatalog;
import com.microjainslee.ra.sbi.openapi.SbiOperation;
import com.microjainslee.ra.sbi.openapi.SbiRouteMatch;
import com.microjainslee.ra.sbi.openapi.headers.SbiHeaderCodec;
import com.microjainslee.ra.sbi.openapi.problem.ProblemDetails;
import com.microjainslee.ra.spi.AbstractResourceAdaptor;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 5GC SBI HTTP/2 Resource Adaptor — catalog dispatch, outbound client with
 * TS 29.500 retry semantics, saga coordinator. Does not implement NF business logic.
 */
public final class SbiHttp2ResourceAdaptor extends AbstractResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(SbiHttp2ResourceAdaptor.class);

    private Vertx vertx;
    private HttpServer server;
    private WebClient webClient;
    private RaBootstrapPort bootstrapPort;

    private SbiOpenApiCatalog catalog;
    private final SbiResiliencePolicy resilience = new SbiResiliencePolicy();
    private final SbiSagaCoordinator sagas = new SbiSagaCoordinator();
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private final AtomicLong peerExchangeCount = new AtomicLong();
    private final ConcurrentHashMap<String, HttpServerResponse> pending = new ConcurrentHashMap<>();

    private String host = "127.0.0.1";
    private int port = 8082;
    private boolean http2ClearText = true;
    private boolean alpn = true;
    private String defaultApiRoot = "http://127.0.0.1:8082";
    private String altSvcHttp3 = "";
    private boolean autoRespondUnmapped = true;

    public SbiHttp2ResourceAdaptor() {
        this.catalog = SbiOpenApiCatalog.loadDefault();
    }

    public void setHost(String host) { this.host = host; }
    public void setPort(int port) { this.port = port; }
    public void setHttp2ClearText(boolean v) { this.http2ClearText = v; }
    public void setAlpn(boolean v) { this.alpn = v; }
    public void setDefaultApiRoot(String v) { this.defaultApiRoot = v; }
    public void setAltSvcHttp3(String v) { this.altSvcHttp3 = v == null ? "" : v; }
    public void setAutoRespondUnmapped(boolean v) { this.autoRespondUnmapped = v; }
    public void setBootstrapPort(RaBootstrapPort port) { this.bootstrapPort = port; }
    public void setCatalog(SbiOpenApiCatalog catalog) { this.catalog = catalog; }

    public String host() { return host; }
    public int configuredPort() { return port; }
    public boolean listening() { return listening.get(); }
    public long peerExchangeCount() { return peerExchangeCount.get(); }
    public SbiOpenApiCatalog catalog() { return catalog; }
    public SbiResiliencePolicy resilience() { return resilience; }
    public SbiSagaCoordinator sagas() { return sagas; }
    public String altSvcHttp3() { return altSvcHttp3; }

    /** Honest client-plane signal — never treat LISTEN as peer UP. */
    public boolean peerTrafficSeen() {
        return peerExchangeCount.get() > 0;
    }

    public synchronized void start() {
        if (vertx != null) {
            return;
        }
        if (catalog == null) {
            catalog = SbiOpenApiCatalog.loadDefault();
        }
        vertx = Vertx.vertx(new VertxOptions());
        HttpServerOptions opts = new HttpServerOptions()
                .setHost(host)
                .setPort(port)
                .setIdleTimeout(60);
        if (http2ClearText) {
            opts.setHttp2ClearTextEnabled(true);
        }
        if (alpn) {
            opts.setUseAlpn(true);
        }
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(16L * 1024 * 1024));
        router.route("/health").handler(ctx ->
                ctx.response().putHeader("content-type", "application/json")
                        .end("{\"status\":\"ok\",\"ra\":\"sbi-http2\",\"module\":\"ra-openapi\"}"));
        router.route().handler(this::handleIngress);

        CountDownLatch latch = new CountDownLatch(1);
        server = vertx.createHttpServer(opts);
        server.requestHandler(router).listen(ar -> {
            if (ar.succeeded()) {
                listening.set(true);
                LOG.info("[ra-openapi/http2] LISTEN {}:{} catalogOps={}",
                        host, ar.result().actualPort(), catalog.size());
            } else {
                LOG.error("[ra-openapi/http2] bind failed: {}", ar.cause().toString());
            }
            latch.countDown();
        });
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        webClient = WebClient.create(vertx, new WebClientOptions()
                .setKeepAlive(true)
                .setMaxPoolSize(64)
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(true)
                .setConnectTimeout(10_000));
    }

    public synchronized void stop() {
        listening.set(false);
        pending.clear();
        if (server != null) {
            server.close();
            server = null;
        }
        if (webClient != null) {
            webClient.close();
            webClient = null;
        }
        if (vertx != null) {
            vertx.close();
            vertx = null;
        }
    }

    public synchronized void rebind() {
        if (listening.get()) {
            raInactive();
        }
        raActive();
    }

    public void sendResponse(String sessionId, int status, String contentType, byte[] body,
                             Map<String, String> headers) {
        HttpServerResponse resp = pending.remove(sessionId);
        if (resp == null || resp.closed()) {
            return;
        }
        if (headers != null) {
            headers.forEach(resp::putHeader);
        }
        if (contentType != null) {
            resp.putHeader("content-type", contentType);
        }
        resp.setStatusCode(status);
        if (body == null || body.length == 0) {
            resp.end();
        } else {
            resp.end(Buffer.buffer(body));
        }
    }

    public void sendOutbound(SbiOutboundCommand cmd) {
        if (webClient == null || cmd == null) {
            return;
        }
        String uriStr = resolveUri(cmd);
        if (uriStr == null) {
            fireOutbound(cmd, 0, Map.of(), new byte[0], false, "unresolvable URI", 0);
            return;
        }
        URI uri = URI.create(uriStr);
        String peerKey = uri.getScheme() + "://" + uri.getAuthority();
        SbiHeaderCodec codec = new SbiHeaderCodec(cmd.headers());
        int maxAttempts = 1 + resilience.effectiveMaxRetries(codec, cmd.maxRetriesOverride());
        doOutboundAttempt(cmd, uri, peerKey, codec, 0, maxAttempts);
    }

    private void doOutboundAttempt(SbiOutboundCommand cmd, URI uri, String peerKey,
                                   SbiHeaderCodec codec, int attempt, int maxAttempts) {
        if (!resilience.allowRequest(peerKey)) {
            fireOutbound(cmd, 503, Map.of(),
                    ProblemDetails.of(503, "Unavailable", "circuit/bulkhead", "NF_CONGESTION")
                            .toJson().getBytes(StandardCharsets.UTF_8),
                    false, "circuit_or_bulkhead", attempt);
            return;
        }
        resilience.acquire(peerKey);
        HttpMethod method = HttpMethod.valueOf(
                (cmd.method() == null ? "POST" : cmd.method()).toUpperCase());
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        int portNum = uri.getPort() <= 0
                ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80)
                : uri.getPort();
        HttpRequest<Buffer> req = webClient.request(method, portNum, uri.getHost(), path)
                .ssl("https".equalsIgnoreCase(uri.getScheme()));
        cmd.headers().forEach(req::putHeader);
        long timeout = codec.maxRspTimeMs().orElse(15_000L);
        req.timeout(timeout);
        Buffer body = Buffer.buffer(cmd.body());
        req.sendBuffer(body, ar -> {
            boolean ok = false;
            int status = 0;
            Map<String, String> rh = new LinkedHashMap<>();
            byte[] rb = new byte[0];
            String err = null;
            String retryAfter = null;
            if (ar.succeeded()) {
                var resp = ar.result();
                status = resp.statusCode();
                resp.headers().forEach(e -> rh.put(e.getKey(), e.getValue()));
                rb = resp.body() == null ? new byte[0] : resp.body().getBytes();
                retryAfter = resp.getHeader("Retry-After");
                ok = status >= 200 && status < 300;
                peerExchangeCount.incrementAndGet();
                if (!ok && SbiResiliencePolicy.shouldRetryStatus(status)
                        && attempt + 1 < maxAttempts) {
                    resilience.release(peerKey, false);
                    long delay = resilience.retryDelayMs(attempt, retryAfter);
                    vertx.setTimer(delay, id ->
                            doOutboundAttempt(cmd, uri, peerKey, codec, attempt + 1, maxAttempts));
                    return;
                }
            } else {
                err = ar.cause() == null ? "send_failed" : ar.cause().toString();
                if (attempt + 1 < maxAttempts) {
                    resilience.release(peerKey, false);
                    long delay = resilience.retryDelayMs(attempt, null);
                    vertx.setTimer(delay, id ->
                            doOutboundAttempt(cmd, uri, peerKey, codec, attempt + 1, maxAttempts));
                    return;
                }
            }
            resilience.release(peerKey, ok);
            if (cmd.sagaId() != null) {
                if (ok) {
                    sagas.markStepDone(cmd.sagaId(), cmd.sagaStepId());
                } else if (!cmd.compensate()) {
                    for (SbiOutboundCommand c : sagas.failAndCompensate(cmd.sagaId())) {
                        sendOutbound(c);
                    }
                }
            }
            fireOutbound(cmd, status, rh, rb, ok, err, attempt + 1);
        });
    }

    private String resolveUri(SbiOutboundCommand cmd) {
        if (cmd.absoluteUri() != null && !cmd.absoluteUri().isBlank()) {
            return cmd.absoluteUri();
        }
        if (cmd.operationId() == null) {
            return null;
        }
        Optional<SbiOperation> op = catalog.byOperationId(cmd.operationId());
        if (op.isEmpty()) {
            return null;
        }
        String root = defaultApiRoot.endsWith("/")
                ? defaultApiRoot.substring(0, defaultApiRoot.length() - 1)
                : defaultApiRoot;
        String path = op.get().pathTemplate();
        if (path.indexOf('{') >= 0) {
            return null;
        }
        return root + path;
    }

    private void fireOutbound(SbiOutboundCommand cmd, int status, Map<String, String> headers,
                              byte[] body, boolean success, String error, int attempts) {
        SbiOutboundCompletedEvent ev = new SbiOutboundCompletedEvent(
                cmd.requestId(), cmd.operationId(), status, headers, body, success, error,
                attempts, cmd.sagaId());
        try {
            if (bootstrapPort != null) {
                bootstrapPort.fireEvent(ev,
                        bootstrapPort.createActivityHandle("sbi-out-" + cmd.requestId()), null);
            } else {
                publish("sbi-out-" + cmd.requestId(), ev);
            }
        } catch (RuntimeException ex) {
            LOG.warn("[ra-openapi/http2] outbound event failed: {}", ex.toString());
        }
    }

    private void handleIngress(RoutingContext ctx) {
        HttpServerRequest req = ctx.request();
        HttpServerResponse resp = ctx.response();
        String method = req.method().name();
        String path = req.path();
        Map<String, String> headers = new LinkedHashMap<>();
        req.headers().forEach(e -> headers.put(e.getKey(), e.getValue()));
        if (altSvcHttp3 != null && !altSvcHttp3.isBlank()) {
            resp.putHeader("Alt-Svc", altSvcHttp3);
        }
        SbiHeaderCodec codec = new SbiHeaderCodec(headers);

        if ("OPTIONS".equalsIgnoreCase(method)) {
            Set<String> allow = catalog.allowedMethods(path);
            if (!allow.isEmpty()) {
                resp.putHeader("Allow", String.join(", ", allow));
                resp.setStatusCode(204).end();
                return;
            }
        }

        Optional<SbiRouteMatch> match = catalog.match(method, path);
        if (match.isEmpty()) {
            ProblemDetails pd = ProblemDetails.of(404, "Not Found",
                    "No SBI operation for " + method + " " + path,
                    "RESOURCE_URI_STRUCTURE_NOT_FOUND");
            resp.putHeader("content-type", ProblemDetails.CONTENT_TYPE)
                    .setStatusCode(404)
                    .end(pd.toJson());
            return;
        }

        SbiRouteMatch rm = match.get();
        String sessionId = UUID.randomUUID().toString();
        pending.put(sessionId, resp);
        resp.exceptionHandler(t -> pending.remove(sessionId));

        Map<String, String> query = new LinkedHashMap<>();
        req.params().forEach(e -> query.put(e.getKey(), e.getValue()));
        byte[] body = ctx.body() == null ? new byte[0] : ctx.body().buffer().getBytes();

        SbiOperationEvent event = new SbiOperationEvent(
                sessionId,
                rm.operation().operationId(),
                rm.operation().apiName(),
                rm.operation().apiVersion(),
                method,
                path,
                rm.pathParams(),
                query,
                headers,
                body,
                SbiHttpVersion.HTTP_2,
                codec.correlationInfo().orElse(null));

        peerExchangeCount.incrementAndGet();
        try {
            if (bootstrapPort != null) {
                bootstrapPort.fireEvent(event,
                        bootstrapPort.createActivityHandle("sbi-" + sessionId), null);
            } else {
                publish("sbi-" + sessionId, event);
            }
        } catch (RuntimeException ex) {
            pending.remove(sessionId);
            ProblemDetails pd = ProblemDetails.of(500, "Internal Error", ex.toString(), "SYSTEM_FAILURE");
            resp.putHeader("content-type", ProblemDetails.CONTENT_TYPE)
                    .setStatusCode(500).end(pd.toJson());
            return;
        }

        if (autoRespondUnmapped) {
            vertx.setTimer(50, id -> {
                if (pending.remove(sessionId) != null && !resp.closed() && !resp.ended()) {
                    ProblemDetails pd = ProblemDetails.of(501, "Not Implemented",
                            "No SBB mapped for " + rm.operation().operationId(),
                            "OPTIONAL_IE_INCORRECT");
                    resp.putHeader("content-type", ProblemDetails.CONTENT_TYPE)
                            .setStatusCode(501).end(pd.toJson());
                }
            });
        }
    }

    @Override
    public void raConfigure() {
        LOG.info("[ra-openapi/http2] configured host={} port={} catalogOps={}",
                host, port, catalog == null ? 0 : catalog.size());
    }

    @Override
    public void raActive() {
        start();
        SbiHttp2AdminBindings.bind(this);
    }

    @Override
    public void raStopping() {
        LOG.info("[ra-openapi/http2] stopping");
    }

    @Override
    public void raInactive() {
        stop();
        SbiHttp2AdminBindings.bind(null);
    }

    @Override
    protected void onContextUnset() {
        stop();
    }
}
