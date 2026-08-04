/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.monitor;

import com.microjainslee.admin.AdminDashboardRegistry;
import com.microjainslee.admin.HttpEndpointCatalog;
import com.microjainslee.admin.HttpEndpointInfo;
import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.admin.RaAdminManifest;
import com.microjainslee.telemetry.TelemetryPort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Observability + RA admin hub — framework-neutral HTTP surface for
 * {@code jainslee-monitor}. Apps adapt transport events into
 * {@link #handle(String, String, Map, String)} without compiling against
 * {@code ra-http-server}.
 *
 * <p>Owned paths:</p>
 * <ul>
 *   <li>{@code /telemetry/*} — steampunk GUI from META-INF/resources</li>
 *   <li>{@code /api/telemetry/*} — when {@link TelemetryPort} provided</li>
 *   <li>{@code /api/admin/dashboards} — JSON list of RA admin manifests</li>
 *   <li>{@code /admin/ra/{raName}/**} — pack static fragments</li>
 *   <li>{@code /api/ra/{raName}/**} — {@link AdminDashboardRegistry#dispatch}</li>
 *   <li>{@code /api/autonomous/health}, {@code /api/ai/*} — optional bridges</li>
 * </ul>
 */
public final class MonitorHandler {

    private static final Logger LOG = LogManager.getLogger(MonitorHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GUI_ROOT = "META-INF/resources";

    private final TelemetryPort telemetry;
    private final Supplier<String> healthJson;
    private final AiMonitorBridge ai;
    private final AdminDashboardRegistry adminRegistry;

    public MonitorHandler(TelemetryPort telemetry) {
        this(telemetry, null, null, AdminDashboardRegistry.load());
    }

    public MonitorHandler(TelemetryPort telemetry,
                          Supplier<String> healthJson,
                          AiMonitorBridge ai) {
        this(telemetry, healthJson, ai, AdminDashboardRegistry.load());
    }

    public MonitorHandler(TelemetryPort telemetry,
                          Supplier<String> healthJson,
                          AiMonitorBridge ai,
                          AdminDashboardRegistry adminRegistry) {
        this.telemetry = telemetry;
        this.healthJson = healthJson;
        this.ai = ai;
        this.adminRegistry = adminRegistry == null
                ? AdminDashboardRegistry.load()
                : adminRegistry;
        HttpEndpointCatalog.shared().replace(
                HttpEndpointCatalog.SOURCE_MICRO_JAINSLEE, hubOwnedEndpoints());
    }

    /**
     * Paths owned by this hub (documented surface for the HTTP admin endpoints table).
     */
    public static List<HttpEndpointInfo> hubOwnedEndpoints() {
        String owner = "micro-jainslee";
        return List.of(
                HttpEndpointInfo.of("GET", "/telemetry/*", owner, "hub GUI (META-INF/resources)"),
                HttpEndpointInfo.of("GET", "/telemetry/partial/ra-nav", owner, "HTMX RA tab strip"),
                HttpEndpointInfo.of("GET", "/telemetry/partial/overview", owner, "HTMX overview panel"),
                HttpEndpointInfo.of("GET", "/api/admin/dashboards", owner, "RA pack manifests JSON"),
                HttpEndpointInfo.of("GET", "/admin/ra/{raName}/**", owner, "pack static panel.html|js|css"),
                HttpEndpointInfo.of("*", "/api/ra/{raName}/**", owner, "pack API dispatch"),
                HttpEndpointInfo.of("*", "/api/telemetry/*", owner, "when TelemetryPort wired"),
                HttpEndpointInfo.of("GET", "/api/autonomous/health", owner, "optional health JSON bridge"),
                HttpEndpointInfo.of("*", "/api/ai/*", owner, "optional AI bridge"));
    }

    /**
     * Optional AI surface — apps that ship {@code jainslee-ai} adapt their
     * engine; others leave null.
     */
    public interface AiMonitorBridge {
        String statusJson() throws Exception;

        String analysisJson() throws Exception;

        String analyzeNowJson() throws Exception;

        String report(String audience) throws Exception;

        void applyConfig(String bodyJson) throws Exception;
    }

    /**
     * Handle a request, or empty if the path is not owned by the monitor hub.
     *
     * @param method HTTP method
     * @param path   request path
     * @param query  query params (may be null)
     * @param body   request body (may be null)
     */
    public Optional<RaAdminHttpResponse> handle(String method, String path,
                                                Map<String, String> query,
                                                String body) {
        String p = path == null ? "/" : path;
        if (p.equals("/telemetry") || p.startsWith("/telemetry/")) {
            if (p.equals("/telemetry/partial/ra-nav") && "GET".equalsIgnoreCase(method)) {
                return Optional.of(raNavHtml(query));
            }
            if (p.equals("/telemetry/partial/overview") && "GET".equalsIgnoreCase(method)) {
                return Optional.of(overviewHtml());
            }
            return Optional.of(serveGui(p));
        }
        if (p.equals("/api/admin/dashboards") && "GET".equalsIgnoreCase(method)) {
            return Optional.of(dashboardsJson());
        }
        if (p.startsWith("/admin/ra/")) {
            return Optional.of(serveAdminStatic(p));
        }
        if (p.startsWith("/api/ra/")) {
            RaAdminHttpRequest req = new RaAdminHttpRequest(method, p, body, query);
            Optional<RaAdminHttpResponse> hit = adminRegistry.dispatch(req);
            return hit.isPresent() ? hit : Optional.of(RaAdminHttpResponse.notFound());
        }
        if (p.startsWith("/api/telemetry")) {
            return Optional.of(telemetryApi(method, p, body));
        }
        if (p.equals("/api/autonomous/health")) {
            return Optional.of(healthJson == null
                    ? RaAdminHttpResponse.json("{\"status\":\"UNKNOWN\"}")
                    : RaAdminHttpResponse.json(healthJson.get()));
        }
        if (p.startsWith("/api/ai")) {
            return Optional.of(aiApi(method, p, query, body));
        }
        return Optional.empty();
    }

    public AdminDashboardRegistry adminRegistry() {
        return adminRegistry;
    }

    private RaAdminHttpResponse dashboardsJson() {
        try {
            ArrayNode arr = JSON.createArrayNode();
            for (RaAdminManifest m : adminRegistry.manifests()) {
                ObjectNode o = arr.addObject();
                o.put("raName", m.raName());
                o.put("tabId", m.tabId());
                o.put("title", m.title());
                o.put("order", m.order());
                o.put("apiBase", m.apiBase());
                o.put("fragmentUrl", "/admin/ra/" + m.raName() + "/panel.html");
                o.put("scriptUrl", "/admin/ra/" + m.raName() + "/panel.js");
                String style = m.resolvedStylePath();
                if (style != null) {
                    o.put("styleUrl", "/admin/ra/" + m.raName() + "/panel.css");
                }
                // Optional hint for tab-dot colouring; packs may override via status API.
                o.put("statusDotHint", "amber");
            }
            return RaAdminHttpResponse.json(JSON.writeValueAsString(arr));
        } catch (Exception ex) {
            return RaAdminHttpResponse.error(500, ex.getMessage());
        }
    }

    private RaAdminHttpResponse serveAdminStatic(String path) {
        // /admin/ra/{raName}/...
        String rest = path.substring("/admin/ra/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return RaAdminHttpResponse.notFound();
        }
        String raName = rest.substring(0, slash);
        String rel = rest.substring(slash + 1);
        Optional<byte[]> bytes = adminRegistry.resolveStatic(raName, rel);
        if (bytes.isEmpty()) {
            return RaAdminHttpResponse.notFound();
        }
        return RaAdminHttpResponse.bytes(contentType("/" + rel), bytes.get());
    }

    private RaAdminHttpResponse overviewHtml() {
        String html = """
                <div class="admin-panel">
                  <p class="text-xs uppercase tracking-[0.25em] text-signal">Overview</p>
                  <h2 class="mt-1 text-xl font-semibold text-slate-50">RA admin hub</h2>
                  <p class="mt-3 text-sm text-ink-mute">
                    Tabs below are discovered from classpath
                    <code class="font-mono text-slate-300">RaAdminDashboardContributor</code>
                    packs. Select <strong class="text-slate-200">SS7</strong>,
                    <strong class="text-slate-200">SMPP</strong>, or
                    <strong class="text-slate-200">HTTP</strong> to configure, apply, and watch
                    peer-ready status (never confuse local LISTEN with link UP).
                  </p>
                  <ul class="mt-4 list-disc space-y-1 pl-5 text-sm text-ink-mute">
                    <li>Shell theme matches Digicom OTA ops (ink / signal).</li>
                    <li>Tab panels load via HTMX fragments from each RA jar.</li>
                    <li>Mutating APIs still require admin session or API key.</li>
                  </ul>
                </div>
                """;
        return RaAdminHttpResponse.text(200, "text/html; charset=utf-8", html);
    }

    private RaAdminHttpResponse raNavHtml(Map<String, String> query) {
        String keyQ = "";
        if (query != null) {
            String k = query.get("key");
            if (k != null && !k.isBlank()) {
                keyQ = "?key=" + urlEncode(k);
            }
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("<button type=\"button\" class=\"hub-tab\"")
                .append(" hx-get=\"/telemetry/partial/overview").append(keyQ).append("\"")
                .append(" hx-target=\"#hub-panel\" hx-swap=\"innerHTML\"")
                .append(" hx-indicator=\"#hub-ind\" data-tab=\"overview\">Overview</button>");
        for (RaAdminManifest m : adminRegistry.manifests()) {
            String frag = "/admin/ra/" + m.raName() + "/panel.html" + keyQ;
            String script = "/admin/ra/" + m.raName() + "/panel.js";
            sb.append("<button type=\"button\" class=\"hub-tab\"")
                    .append(" hx-get=\"").append(escAttr(frag)).append("\"")
                    .append(" hx-target=\"#hub-panel\" hx-swap=\"innerHTML\"")
                    .append(" hx-indicator=\"#hub-ind\"")
                    .append(" data-tab=\"").append(escAttr(m.tabId())).append("\"")
                    .append(" data-ra-name=\"").append(escAttr(m.raName())).append("\"")
                    .append(" data-api-base=\"").append(escAttr(m.apiBase())).append("\"")
                    .append(" data-script=\"").append(escAttr(script)).append("\">")
                    .append(escHtml(m.title()))
                    .append(" <span class=\"tab-dot amber\" id=\"dot-")
                    .append(escAttr(m.tabId())).append("\"></span></button>");
        }
        return RaAdminHttpResponse.text(200, "text/html; charset=utf-8", sb.toString());
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escAttr(String s) {
        return escHtml(s);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private RaAdminHttpResponse serveGui(String path) {
        String rest = path.substring("/telemetry".length());
        if (rest.isEmpty() || rest.equals("/")) {
            rest = "/index.html";
        }
        String resource = GUI_ROOT + rest;
        try (InputStream in = classLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return RaAdminHttpResponse.notFound();
            }
            return RaAdminHttpResponse.bytes(contentType(rest), in.readAllBytes());
        } catch (Exception ex) {
            LOG.warn("[monitor] failed to serve {}: {}", resource, ex.getMessage());
            return RaAdminHttpResponse.notFound();
        }
    }

    private RaAdminHttpResponse telemetryApi(String method, String path, String body) {
        if (telemetry == null) {
            return RaAdminHttpResponse.text(503, "text/plain; charset=utf-8",
                    "telemetry disabled");
        }
        try {
            if (path.equals("/api/telemetry/snapshot")) {
                return RaAdminHttpResponse.json(JSON.writeValueAsString(telemetry.snapshot()));
            }
            if (path.equals("/api/telemetry/metrics")) {
                return RaAdminHttpResponse.text(200, "text/plain; charset=utf-8",
                        telemetry.scrape());
            }
            if (path.equals("/api/telemetry/alarms")) {
                return RaAdminHttpResponse.json(
                        JSON.writeValueAsString(telemetry.alarmEngine().active()));
            }
            if (path.startsWith("/api/telemetry/alarms/") && path.endsWith("/clear")
                    && "POST".equalsIgnoreCase(method)) {
                String id = path.substring("/api/telemetry/alarms/".length(),
                        path.length() - "/clear".length());
                telemetry.alarmEngine().clear(id);
                return RaAdminHttpResponse.json("{\"status\":\"ok\"}");
            }
            if (path.equals("/api/telemetry/config") && "POST".equalsIgnoreCase(method)) {
                JsonNode node = readBody(body);
                if (node != null && node.has("autoReconfig")) {
                    telemetry.setAutoReconfigEnabled(node.get("autoReconfig").asBoolean());
                }
                return RaAdminHttpResponse.json("{\"status\":\"ok\"}");
            }
        } catch (Exception ex) {
            return RaAdminHttpResponse.text(500, "text/plain; charset=utf-8",
                    "telemetry error: " + ex.getMessage());
        }
        return RaAdminHttpResponse.notFound();
    }

    private RaAdminHttpResponse aiApi(String method, String path,
                                      Map<String, String> query, String body) {
        if (ai == null) {
            return RaAdminHttpResponse.json(
                    "{\"available\":false,\"reason\":\"AI module not installed\"}");
        }
        try {
            return switch (path) {
                case "/api/ai/status" -> RaAdminHttpResponse.json(ai.statusJson());
                case "/api/ai/analysis" -> {
                    String j = ai.analysisJson();
                    yield j == null
                            ? RaAdminHttpResponse.noContent()
                            : RaAdminHttpResponse.json(j);
                }
                case "/api/ai/analyze" -> {
                    String j = ai.analyzeNowJson();
                    yield j == null
                            ? RaAdminHttpResponse.text(503, "application/json",
                            "{\"error\":\"AI unavailable — check api-key\"}")
                            : RaAdminHttpResponse.json(j);
                }
                case "/api/ai/report" -> {
                    String audience = query == null ? null : query.get("audience");
                    yield RaAdminHttpResponse.text(200, "text/plain; charset=utf-8",
                            ai.report(audience));
                }
                case "/api/ai/config" -> {
                    if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                        ai.applyConfig(body);
                    }
                    yield RaAdminHttpResponse.json("{\"status\":\"ok\"}");
                }
                default -> RaAdminHttpResponse.notFound();
            };
        } catch (Exception ex) {
            return RaAdminHttpResponse.text(500, "text/plain; charset=utf-8",
                    "ai error: " + ex.getMessage());
        }
    }

    private static JsonNode readBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : MonitorHandler.class.getClassLoader();
    }

    static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
