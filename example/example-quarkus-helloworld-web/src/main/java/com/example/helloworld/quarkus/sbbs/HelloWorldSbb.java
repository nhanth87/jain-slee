package com.example.helloworld.quarkus.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SBB that handles HTTP web requests from ra-http-server with multi-endpoint routing.
 *
 * <p>Demonstrates SLEE-compliant HTTP routing: the SBB receives ALL non-/health
 * requests as {@link HttpWebRequestEvent} from ra-http-server, then routes based on
 * {@code event.getMethod()} + {@code event.getPath()} using Java 25 switch.</p>
 *
 * <p>This is the SLEE-compliant pattern: the SBB receives events only
 * through the SLEE pipeline, never directly from HTTP.</p>
 *
 * <h3>Endpoints</h3>
 * <pre>
 * GET  /              → welcome page (lists all endpoints)
 * GET  /hello         → {"message":"Hello World"}
 * GET  /bye/book      → {"message":"Goodbye from book!","ts":"..."}
 * GET  /api/status    → {"status":"running","uptime":...}
 * GET  /api/time      → {"time":"...","zone":"..."}
 * POST /echo          → echoes request body back
 * ANY  /{other}       → 404 + available endpoints list
 * </pre>
 */
public final class HelloWorldSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(HelloWorldSbb.class);

    private final long startTime = System.currentTimeMillis();
    private final MicroSleeContainer container;

    /** Injected by container at activation time. Must match HttpServerRaEndpoint.getRaName(). */
    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort httpCommandPort;

    public HelloWorldSbb(MicroSleeContainer container) {
        this.container = container;
    }

    @Override
    public void sbbCreate() {
        LOG.debug("HelloWorldSbb created");
    }

    @Override
    public void sbbActivate() {
        LOG.debug("HelloWorldSbb activated");
    }

    @Override
    public void sbbPassivate() { }

    @Override
    public void sbbRemove() { }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof HttpWebRequestEvent req) {
            onWebRequest(req);
        }
    }

    // ── HTTP routing ─────────────────────────────────────────────────

    private void onWebRequest(HttpWebRequestEvent event) {
        String method = event.getMethod();
        String path = event.getPath();
        String userAgent = event.getUserAgent() != null ? event.getUserAgent() : "unknown";
        LOG.info("[HelloWorld] {} {} userAgent={}", method, path, userAgent);

        // Route: method + path → handler
        var result = route(method, path, event.getBody(), userAgent);
        httpCommandPort.sendCommand(new HttpServerCommand.HttpResponseCommand(
                event.getSessionId(), result.status(), "application/json", result.body()));
    }

    /**
     * Routes HTTP method+path to a response.
     * Add new endpoints by adding cases here — no framework annotations needed.
     */
    private RouteResult route(String method, String path, String body, String ua) {
        return switch (method + " " + path) {
            // ── Welcome / health ──
            case "GET /" -> ok(json(
                    kv("message", "Welcome to JAIN SLEE HelloWorld!"),
                    kv("endpoints", endpoints())));

            // ── Demo endpoints ──
            case "GET /hello" -> ok(json(
                    kv("message", "Hello World from JAIN SLEE!"),
                    kv("userAgent", ua)));

            case "GET /bye/book" -> ok(json(
                    kv("message", "Goodbye from book!"),
                    kv("ts", java.time.Instant.now().toString())));

            // ── API endpoints ──
            case "GET /api/status" -> ok(json(
                    kv("status", "running"),
                    kv("uptimeMs", System.currentTimeMillis() - startTime),
                    kv("javaVersion", System.getProperty("java.version"))));

            case "GET /api/time" -> ok(json(
                    kv("time", java.time.Instant.now().toString()),
                    kv("zone", java.time.ZoneId.systemDefault().toString())));

            // ── Echo ──
            case "POST /echo" -> ok(json(
                    kv("echo", body != null ? body : "(empty body)"),
                    kv("method", method),
                    kv("path", path)));

            // ── 404 fallback ──
            default -> notFound(json(
                    kv("error", "Not found"),
                    kv("method", method),
                    kv("path", path),
                    kv("availableEndpoints", endpoints())));
        };
    }

    // ── JSON helpers (inline, no Jackson needed for simple responses) ──

    private record RouteResult(int status, String body) {}

    private RouteResult ok(String body) {
        return new RouteResult(200, body);
    }

    private RouteResult notFound(String body) {
        return new RouteResult(404, body);
    }

    private String kv(String key, Object value) {
        return "\"" + key + "\":" + (value instanceof Number || value instanceof Boolean
                ? String.valueOf(value)
                : "\"" + String.valueOf(value).replace("\"", "\\\"") + "\"");
    }

    private String json(String... pairs) {
        return "{" + String.join(",", pairs) + "}";
    }

    private String endpoints() {
        return "[\"GET /\",\"GET /hello\",\"GET /bye/book\",\"GET /api/status\","
                + "\"GET /api/time\",\"POST /echo\"]";
    }
}
