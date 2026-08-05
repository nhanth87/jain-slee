/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http3;

import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.ra.sbi.http2.events.SbiOperationEvent;
import com.microjainslee.ra.sbi.http2.resilience.SbiResiliencePolicy;
import com.microjainslee.ra.sbi.http2.resilience.SbiSagaCoordinator;
import com.microjainslee.ra.sbi.openapi.SbiHttpVersion;
import com.microjainslee.ra.sbi.openapi.SbiOpenApiCatalog;
import com.microjainslee.ra.sbi.openapi.SbiRouteMatch;
import com.microjainslee.ra.sbi.openapi.headers.SbiHeaderCodec;
import com.microjainslee.ra.sbi.openapi.problem.ProblemDetails;
import com.microjainslee.ra.spi.AbstractResourceAdaptor;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
 * Experimental SBI HTTP/3 RA inside {@code ra-openapi}.
 * Lab path: Vert.x <strong>4.5</strong> TCP HTTP/2 cleartext (same engine as HTTP/2 RA).
 * Quic/HTTP/3: Vert.x <strong>5.1</strong> via {@link Vertx5QuicSupport} isolated ClassLoader —
 * never shares {@code io.vertx} with the Vert.x 4 parent loader.
 */
public final class SbiHttp3ResourceAdaptor extends AbstractResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(SbiHttp3ResourceAdaptor.class);

    private Vertx vertx;
    private HttpServer server;
    private RaBootstrapPort bootstrapPort;
    private SbiOpenApiCatalog catalog = SbiOpenApiCatalog.loadDefault();
    private final SbiResiliencePolicy resilience = new SbiResiliencePolicy();
    private final SbiSagaCoordinator sagas = new SbiSagaCoordinator();
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private final AtomicBoolean quicReady = new AtomicBoolean(false);
    private final AtomicLong peerExchangeCount = new AtomicLong();
    private final ConcurrentHashMap<String, HttpServerResponse> pending = new ConcurrentHashMap<>();

    private String host = "127.0.0.1";
    private int tcpPort = 8083;
    private int quicPort = 8443;
    private boolean autoRespondUnmapped = true;
    private String quicError = "";

    public void setHost(String host) { this.host = host; }
    public void setTcpPort(int port) { this.tcpPort = port; }
    public void setQuicPort(int port) { this.quicPort = port; }
    public void setBootstrapPort(RaBootstrapPort port) { this.bootstrapPort = port; }
    public void setCatalog(SbiOpenApiCatalog catalog) { this.catalog = catalog; }
    public void setAutoRespondUnmapped(boolean v) { this.autoRespondUnmapped = v; }

    public String host() { return host; }
    public int tcpPort() { return tcpPort; }
    public int quicPort() { return quicPort; }
    public boolean listening() { return listening.get(); }
    public boolean quicReady() { return quicReady.get(); }
    public String quicError() { return quicError; }
    public long peerExchangeCount() { return peerExchangeCount.get(); }
    public boolean peerTrafficSeen() { return peerExchangeCount.get() > 0; }
    public SbiOpenApiCatalog catalog() { return catalog; }
    public SbiResiliencePolicy resilience() { return resilience; }
    public SbiSagaCoordinator sagas() { return sagas; }

    public synchronized void start() {
        if (vertx != null) {
            return;
        }
        if (catalog == null) {
            catalog = SbiOpenApiCatalog.loadDefault();
        }
        vertx = Vertx.vertx(new VertxOptions());
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(16L * 1024 * 1024));
        router.route("/health").handler(ctx ->
                ctx.response().putHeader("content-type", "application/json")
                        .end("{\"status\":\"ok\",\"ra\":\"sbi-http3\",\"module\":\"ra-openapi\",\"quic\":"
                                + quicReady.get() + "}"));
        router.route().handler(this::handleIngress);

        // TCP HTTP/2 cleartext for lab/tests (honest TCP_FALLBACK until Quic up).
        CountDownLatch latch = new CountDownLatch(1);
        HttpServerOptions opts = new HttpServerOptions()
                .setHost(host)
                .setPort(tcpPort)
                .setHttp2ClearTextEnabled(true)
                .setUseAlpn(true)
                .setIdleTimeout(60);
        server = vertx.createHttpServer(opts);
        server.requestHandler(router).listen(ar -> {
            listening.set(ar.succeeded());
            if (ar.succeeded()) {
                LOG.info("[ra-openapi/http3] TCP LISTEN {}:{} catalogOps={} (QUIC experimental)",
                        host, ar.result().actualPort(), catalog.size());
                probeQuic();
            } else {
                quicError = ar.cause() == null ? "tcp_bind_failed" : ar.cause().toString();
                LOG.error("[ra-openapi/http3] TCP bind failed: {}", quicError);
            }
            latch.countDown();
        });
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Best-effort Quic probe via isolated Vert.x 5 ClassLoader.
     * Does not start a Quic server on the Vert.x 4 event loop (API incompatible);
     * reports {@code quicReady} only when Vert.x 5 Quic API is loadable.
     * Full Quic bind is deferred to a Vert.x-5-only micro-RA process when native transport exists.
     */
    private void probeQuic() {
        String probe = Vertx5QuicSupport.probeQuicApi();
        if (probe.isEmpty()) {
            // API present in isolated loader — still not LIVE until peer Quic evidenced.
            quicReady.set(false);
            quicError = "quic_api_present_awaiting_native_bind";
            LOG.info("[ra-openapi/http3] Vert.x 5 Quic API present (isolated); native bind deferred. quicPort={}",
                    quicPort);
        } else {
            quicReady.set(false);
            quicError = probe;
            LOG.info("[ra-openapi/http3] Quic unavailable: {}", quicError);
        }
    }

    public synchronized void stop() {
        listening.set(false);
        quicReady.set(false);
        pending.clear();
        if (server != null) {
            server.close();
            server = null;
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

    @Override
    public void raConfigure() {
        LOG.info("[ra-openapi/http3] configured tcp={} quic={} catalogOps={}",
                tcpPort, quicPort, catalog.size());
    }

    @Override
    public void raActive() {
        start();
        com.microjainslee.ra.sbi.http3.admin.SbiHttp3AdminBindings.bind(this);
    }

    @Override
    public void raStopping() {
        LOG.info("[ra-openapi/http3] stopping");
    }

    @Override
    public void raInactive() {
        stop();
        com.microjainslee.ra.sbi.http3.admin.SbiHttp3AdminBindings.bind(null);
    }

    public void sendResponse(String sessionId, int status, String contentType, byte[] body) {
        HttpServerResponse resp = pending.remove(sessionId);
        if (resp == null || resp.closed()) {
            return;
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

    private void handleIngress(RoutingContext ctx) {
        HttpServerRequest req = ctx.request();
        HttpServerResponse resp = ctx.response();
        String method = req.method().name();
        String path = req.path();
        Map<String, String> headers = new LinkedHashMap<>();
        req.headers().forEach(e -> headers.put(e.getKey(), e.getValue()));
        // Advertise HTTP/3 when Quic path is honestly ready (never on LISTEN alone).
        if (quicReady.get()) {
            resp.putHeader("Alt-Svc", "h3=\":" + quicPort + "\"; ma=86400");
        }

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
                    .setStatusCode(404).end(pd.toJson());
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        pending.put(sessionId, resp);
        resp.exceptionHandler(t -> pending.remove(sessionId));
        Map<String, String> query = new LinkedHashMap<>();
        req.params().forEach(e -> query.put(e.getKey(), e.getValue()));
        byte[] body = ctx.body() == null ? new byte[0] : ctx.body().buffer().getBytes();
        // TCP lab path is HTTP/2; HTTP_3 only when Quic peer evidenced (future bind).
        SbiHttpVersion ver = SbiHttpVersion.HTTP_2;
        SbiHeaderCodec codec = new SbiHeaderCodec(headers);
        SbiRouteMatch rm = match.get();

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
                ver,
                codec.correlationInfo().orElse(null));
        peerExchangeCount.incrementAndGet();
        try {
            if (bootstrapPort != null) {
                bootstrapPort.fireEvent(event,
                        bootstrapPort.createActivityHandle("sbi3-" + sessionId), null);
            } else {
                publish("sbi3-" + sessionId, event);
            }
        } catch (RuntimeException ex) {
            pending.remove(sessionId);
            resp.putHeader("content-type", ProblemDetails.CONTENT_TYPE)
                    .setStatusCode(500)
                    .end(ProblemDetails.of(500, "Internal Error", ex.toString(), "SYSTEM_FAILURE").toJson());
            return;
        }
        if (autoRespondUnmapped) {
            vertx.setTimer(50, id -> {
                if (pending.remove(sessionId) != null && !resp.closed() && !resp.ended()) {
                    resp.putHeader("content-type", ProblemDetails.CONTENT_TYPE)
                            .setStatusCode(501)
                            .end(ProblemDetails.of(501, "Not Implemented",
                                    "No SBB mapped for " + rm.operation().operationId(),
                                    "OPTIONAL_IE_INCORRECT").toJson());
                }
            });
        }
    }

    @Override
    protected void onContextUnset() {
        stop();
    }
}
