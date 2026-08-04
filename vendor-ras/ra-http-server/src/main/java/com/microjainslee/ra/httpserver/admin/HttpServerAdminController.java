/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.httpserver.admin;

import com.microjainslee.admin.HttpEndpointCatalog;
import com.microjainslee.admin.HttpEndpointInfo;
import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.admin.RaAdminJson;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for http-server-ra. {@code listening}/{@code active} means local
 * listen — intentional green-tab signal for a <em>server</em> RA (ADR 0003).
 * Not peer UP (HTTP has no peer plane).
 */
public final class HttpServerAdminController {

    private static final Logger LOG = LogManager.getLogger(HttpServerAdminController.class);

    public RaAdminHttpResponse status(RaAdminHttpRequest ignored) {
        return RaAdminJson.ok(statusMap());
    }

    public RaAdminHttpResponse statusHtml(RaAdminHttpRequest ignored) {
        Map<String, Object> st = statusMap();
        boolean listening = Boolean.TRUE.equals(st.get("listening"));
        String detail = String.valueOf(st.getOrDefault("detail", ""));
        String badge = listening
                ? "<span class=\"link-status-badge link-status-badge--ok\">LISTEN</span>"
                : "<span class=\"link-status-badge link-status-badge--mute\">DOWN</span>";
        String html = "<div class=\"link-status-panel\">"
                + "<div class=\"link-status-head\"><h3>HTTP Server</h3>" + badge + "</div>"
                + "<p class=\"link-status-detail\">" + RaAdminJson.escHtml(detail) + "</p>"
                + "<div class=\"kv\"><span class=\"k\">host</span><span class=\"v\">"
                + RaAdminJson.escHtml(String.valueOf(st.getOrDefault("host", "")))
                + "</span></div>"
                + "<div class=\"kv\"><span class=\"k\">port</span><span class=\"v\">"
                + RaAdminJson.escHtml(String.valueOf(st.getOrDefault("port", "")))
                + "</span></div>"
                + "<p class=\"link-status-detail\" style=\"margin-top:0.5rem\">"
                + RaAdminJson.escHtml(String.valueOf(st.getOrDefault("note", "")))
                + "</p></div>";
        return RaAdminHttpResponse.text(200, "text/html; charset=utf-8", html)
                .withHeader("Vary", "HX-Request");
    }

    public RaAdminHttpResponse getConfig(RaAdminHttpRequest ignored) {
        HttpServerResourceAdaptor ra = HttpServerAdminBindings.adaptor();
        String host = ra != null ? ra.host() : HttpServerAdminBindings.configuredHost();
        int port = ra != null ? ra.configuredPort() : HttpServerAdminBindings.configuredPort();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", host);
        out.put("port", port);
        return RaAdminJson.ok(out);
    }

    public RaAdminHttpResponse putConfig(RaAdminHttpRequest req) {
        String body = req == null ? null : req.body();
        if (body == null || body.isBlank()) {
            return RaAdminJson.status(400, Map.of("ok", false, "error", "empty body"));
        }
        try {
            var tree = RaAdminJson.mapper().readTree(body);
            String host = tree.hasNonNull("host") ? tree.get("host").asText() : null;
            Integer port = tree.has("port") && !tree.get("port").isNull()
                    ? tree.get("port").asInt() : null;
            if (host != null) {
                HttpServerAdminBindings.setConfigured(host,
                        port == null ? HttpServerAdminBindings.configuredPort() : port);
            } else if (port != null) {
                HttpServerAdminBindings.setConfigured(
                        HttpServerAdminBindings.configuredHost(), port);
            }
            HttpServerResourceAdaptor ra = HttpServerAdminBindings.adaptor();
            if (ra != null) {
                if (host != null) {
                    ra.setHost(host);
                }
                if (port != null) {
                    ra.setPort(port);
                }
            }
            return getConfig(req);
        } catch (Exception ex) {
            return RaAdminJson.status(400, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "bad config" : ex.getMessage()));
        }
    }

    public RaAdminHttpResponse rebind(RaAdminHttpRequest ignored) {
        HttpServerResourceAdaptor ra = HttpServerAdminBindings.adaptor();
        if (ra == null) {
            return RaAdminJson.status(503, Map.of("ok", false, "error", "no RA bound"));
        }
        try {
            ra.setHost(HttpServerAdminBindings.configuredHost());
            ra.setPort(HttpServerAdminBindings.configuredPort());
            ra.rebind();
            LOG.info("[http-admin] rebound {}:{}", ra.host(), ra.port());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("active", ra.isActive());
            out.put("listening", ra.isActive());
            out.put("host", ra.host());
            out.put("port", ra.port());
            return RaAdminJson.ok(out);
        } catch (RuntimeException ex) {
            return RaAdminJson.status(500, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "rebind failed" : ex.getMessage()));
        }
    }

    /** JSON list of known HTTP endpoints (RA + catalog contributors). */
    public RaAdminHttpResponse endpoints(RaAdminHttpRequest ignored) {
        List<HttpEndpointInfo> list = collectEndpoints();
        List<Map<String, String>> rows = new ArrayList<>(list.size());
        for (HttpEndpointInfo e : list) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("method", e.method());
            row.put("path", e.path());
            row.put("owner", e.owner());
            row.put("note", e.note());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", rows.size());
        out.put("endpoints", rows);
        return RaAdminJson.ok(out);
    }

    /** HTMX fragment: Digicom table Method | Path | Owner | Note. */
    public RaAdminHttpResponse endpointsHtml(RaAdminHttpRequest ignored) {
        List<HttpEndpointInfo> list = collectEndpoints();
        StringBuilder sb = new StringBuilder(512 + list.size() * 96);
        sb.append("<div class=\"link-status-table-wrap\" id=\"http-endpoints-panel\">")
                .append("<p class=\"link-status-caption\">Endpoints (")
                .append(list.size())
                .append(")</p>")
                .append("<table class=\"link-status-table link-status-table--endpoints\">")
                .append("<thead><tr>")
                .append("<th>Method</th><th>Path</th><th>Owner</th><th>Note</th>")
                .append("</tr></thead><tbody>");
        if (list.isEmpty()) {
            sb.append("<tr><td colspan=\"4\" class=\"link-status-detail\">")
                    .append("No endpoints registered</td></tr>");
        } else {
            for (HttpEndpointInfo e : list) {
                sb.append("<tr>")
                        .append("<td class=\"link-status-mono\">")
                        .append(RaAdminJson.escHtml(e.method())).append("</td>")
                        .append("<td class=\"link-status-mono\">")
                        .append(RaAdminJson.escHtml(e.path())).append("</td>")
                        .append("<td>").append(RaAdminJson.escHtml(e.owner())).append("</td>")
                        .append("<td>").append(RaAdminJson.escHtml(e.note())).append("</td>")
                        .append("</tr>");
            }
        }
        sb.append("</tbody></table></div>");
        return RaAdminHttpResponse.text(200, "text/html; charset=utf-8", sb.toString())
                .withHeader("Vary", "HX-Request")
                .withHeader("Cache-Control", "no-store");
    }

    private static List<HttpEndpointInfo> collectEndpoints() {
        HttpEndpointCatalog.shared().replace(
                HttpEndpointCatalog.SOURCE_HTTP_SERVER_RA, raOwnedEndpoints());
        return HttpEndpointCatalog.shared().snapshot();
    }

    /** Routes the Vert.x RA owns locally (listen + /health + catch-all). */
    static List<HttpEndpointInfo> raOwnedEndpoints() {
        HttpServerResourceAdaptor ra = HttpServerAdminBindings.adaptor();
        String host = ra != null ? ra.host() : HttpServerAdminBindings.configuredHost();
        int port = ra != null ? ra.port() : HttpServerAdminBindings.configuredPort();
        boolean listening = ra != null && ra.isActive();
        String owner = "http-server-ra";
        String listenNote = listening
                ? "local LISTEN (not peer UP)"
                : "configured bind target (not listening)";
        return List.of(
                HttpEndpointInfo.of("LISTEN", host + ":" + port, owner, listenNote),
                HttpEndpointInfo.of("GET", "/health", owner,
                        "RA short-circuit JSON before SBB"),
                HttpEndpointInfo.of("*", "/*", owner,
                        "catch-all → HttpWebRequestEvent to SBB"));
    }

    private static Map<String, Object> statusMap() {
        HttpServerResourceAdaptor ra = HttpServerAdminBindings.adaptor();
        boolean listening = ra != null && ra.isActive();
        String host = ra != null ? ra.host() : HttpServerAdminBindings.configuredHost();
        int port = ra != null ? ra.port() : HttpServerAdminBindings.configuredPort();
        String cfgHost = HttpServerAdminBindings.configuredHost();
        int cfgPort = HttpServerAdminBindings.configuredPort();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", listening);
        m.put("listening", listening);
        m.put("bound", ra != null);
        m.put("host", host);
        m.put("port", port);
        m.put("configuredHost", cfgHost);
        m.put("configuredPort", cfgPort);
        m.put("detail", listening
                ? "http=listen;host=" + host + ";port=" + port
                : "http=down");
        m.put("note", "listening=green (server accepts requests); not a peer protocol");
        return m;
    }
}
