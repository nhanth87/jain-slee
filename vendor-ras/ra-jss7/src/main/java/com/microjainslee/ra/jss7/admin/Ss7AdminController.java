/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.jss7.admin;

import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.admin.RaAdminJson;
import com.microjainslee.ra.jss7.Ss7RaEndpoint;
import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Admin API for ra-jss7. Link-status truth: {@code routeReady} comes only from
 * {@link Ss7ResourceAdaptor#isM3uaRouteReady()} — never from {@code isActive()} alone.
 *
 * <p>When {@link Ss7AdminBindings#bindHooks} is set (OTA), validate/save/apply/start/stop
 * go through the app plane. Otherwise the controller mutates the bound RA directly.
 */
public final class Ss7AdminController {

    private static final Logger LOG = LogManager.getLogger(Ss7AdminController.class);

    public RaAdminHttpResponse status(RaAdminHttpRequest ignored) {
        return RaAdminJson.ok(statusMap());
    }

    /** HTMX fragment — Digicom tables; poll via {@code hx-trigger="load, every 4s"}. */
    public RaAdminHttpResponse statusHtml(RaAdminHttpRequest ignored) {
        String html = Ss7StatusHtml.render(statusMap());
        return RaAdminHttpResponse.text(200, "text/html; charset=utf-8", html)
                .withHeader("Vary", "HX-Request");
    }

    public RaAdminHttpResponse config(RaAdminHttpRequest ignored) {
        String json = null;
        Supplier<String> cfgHook = Ss7AdminBindings.configJsonHook();
        if (cfgHook != null) {
            json = cfgHook.get();
        }
        if (json == null || json.isBlank()) {
            json = Ss7AdminBindings.lastConfigJson();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            out.put("config", null);
            out.put("note", "no config held");
            return RaAdminJson.ok(out);
        }
        try {
            out.put("config", RaAdminJson.mapper().readTree(json));
        } catch (Exception ex) {
            out.put("config", json);
            out.put("note", "raw string (not valid JSON tree)");
        }
        return RaAdminJson.ok(out);
    }

    public RaAdminHttpResponse validate(RaAdminHttpRequest req) {
        String body = req == null ? null : req.body();
        if (body == null || body.isBlank()) {
            return RaAdminJson.status(400, Map.of("ok", false, "error", "empty body"));
        }
        Function<String, String> hook = Ss7AdminBindings.validateHook();
        if (hook != null) {
            return jsonFromHook(hook.apply(body), 400);
        }
        try {
            Ss7Config cfg = Ss7ConfigLoader.parse(body);
            String name = cfg == null ? null : cfg.stackName();
            Ss7AdminBindings.setLastConfigJson(body);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("stackName", name == null ? "" : name);
            return RaAdminJson.ok(out);
        } catch (RuntimeException ex) {
            return RaAdminJson.status(400, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "validate failed" : ex.getMessage()));
        }
    }

    public RaAdminHttpResponse apply(RaAdminHttpRequest req) {
        String body = req == null ? null : req.body();
        if (body == null || body.isBlank()) {
            return RaAdminJson.status(400, Map.of("ok", false, "error", "empty body"));
        }
        Function<String, String> save = Ss7AdminBindings.saveConfigHook();
        Supplier<String> applyHook = Ss7AdminBindings.applyHook();
        if (save != null || applyHook != null) {
            if (save != null) {
                String saved = save.apply(body);
                if (saved != null && saved.startsWith("{\"ok\":false")) {
                    return jsonFromHook(saved, 400);
                }
            } else {
                Ss7AdminBindings.setLastConfigJson(body);
            }
            if (applyHook == null) {
                return RaAdminJson.status(503, Map.of("ok", false, "error", "apply hook not bound"));
            }
            try {
                String detail = applyHook.get();
                Ss7ResourceAdaptor ra = Ss7AdminBindings.adaptor();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("applied", true);
                out.put("detail", detail == null ? "" : detail);
                out.put("active", ra != null && ra.isActive());
                out.put("routeReady", ra != null && ra.isM3uaRouteReady());
                return RaAdminJson.ok(out);
            } catch (RuntimeException ex) {
                LOG.warn("[ss7-admin] apply hook failed: {}", ex.getMessage());
                return RaAdminJson.status(500, Map.of("ok", false, "error",
                        ex.getMessage() == null ? "apply failed" : ex.getMessage()));
            }
        }
        try {
            Ss7Config cfg = Ss7ConfigLoader.parse(body);
            Ss7AdminBindings.setLastConfigJson(body);
            Ss7ResourceAdaptor ra = Ss7AdminBindings.adaptor();
            if (ra == null) {
                return RaAdminJson.ok(Map.of(
                        "ok", true,
                        "applied", false,
                        "note", "validated+stored; no RA bound — call Ss7AdminBindings.bind after registerRa"));
            }
            boolean wasActive = ra.isActive();
            if (wasActive) {
                ra.raInactive();
            }
            ra.setSs7Config(cfg);
            if (wasActive || Ss7AdminBindings.endpoint() != null) {
                ra.raActive();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("applied", true);
            out.put("active", ra.isActive());
            out.put("routeReady", ra.isM3uaRouteReady());
            return RaAdminJson.ok(out);
        } catch (RuntimeException ex) {
            LOG.warn("[ss7-admin] apply failed: {}", ex.getMessage());
            return RaAdminJson.status(500, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "apply failed" : ex.getMessage()));
        }
    }

    public RaAdminHttpResponse start(RaAdminHttpRequest ignored) {
        Supplier<String> start = Ss7AdminBindings.startHook();
        if (start != null) {
            try {
                String detail = start.get();
                Ss7ResourceAdaptor ra = Ss7AdminBindings.adaptor();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("detail", detail == null ? "" : detail);
                out.put("active", ra != null && ra.isActive());
                out.put("routeReady", ra != null && ra.isM3uaRouteReady());
                return RaAdminJson.ok(out);
            } catch (RuntimeException ex) {
                return RaAdminJson.status(500, Map.of("ok", false, "error",
                        ex.getMessage() == null ? "start failed" : ex.getMessage()));
            }
        }
        Ss7ResourceAdaptor ra = Ss7AdminBindings.adaptor();
        if (ra == null) {
            return RaAdminJson.status(503, Map.of("ok", false, "error", "no RA bound"));
        }
        try {
            if (!ra.isActive()) {
                ra.raActive();
            }
            return RaAdminJson.ok(Map.of(
                    "ok", true,
                    "active", ra.isActive(),
                    "routeReady", ra.isM3uaRouteReady()));
        } catch (RuntimeException ex) {
            return RaAdminJson.status(500, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "start failed" : ex.getMessage()));
        }
    }

    public RaAdminHttpResponse stop(RaAdminHttpRequest ignored) {
        Supplier<String> stop = Ss7AdminBindings.stopHook();
        if (stop != null) {
            try {
                String detail = stop.get();
                Ss7ResourceAdaptor ra = Ss7AdminBindings.adaptor();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("detail", detail == null ? "" : detail);
                out.put("active", ra != null && ra.isActive());
                out.put("routeReady", ra != null && ra.isM3uaRouteReady());
                return RaAdminJson.ok(out);
            } catch (RuntimeException ex) {
                return RaAdminJson.status(500, Map.of("ok", false, "error",
                        ex.getMessage() == null ? "stop failed" : ex.getMessage()));
            }
        }
        Ss7ResourceAdaptor ra = Ss7AdminBindings.adaptor();
        if (ra == null) {
            return RaAdminJson.ok(Map.of("ok", true, "active", false, "note", "noop"));
        }
        try {
            ra.raInactive();
            return RaAdminJson.ok(Map.of(
                    "ok", true,
                    "active", ra.isActive(),
                    "routeReady", ra.isM3uaRouteReady()));
        } catch (RuntimeException ex) {
            return RaAdminJson.status(500, Map.of("ok", false, "error",
                    ex.getMessage() == null ? "stop failed" : ex.getMessage()));
        }
    }

    private static RaAdminHttpResponse jsonFromHook(String result, int fallbackStatus) {
        if (result == null || result.isBlank()) {
            return RaAdminJson.ok(Map.of("ok", true));
        }
        String trimmed = result.trim();
        if (trimmed.startsWith("{")) {
            int status = trimmed.contains("\"ok\":false") || trimmed.contains("\"ok\": false")
                    ? fallbackStatus : 200;
            return RaAdminHttpResponse.json(status, result);
        }
        return RaAdminJson.status(fallbackStatus, Map.of("ok", false, "raw", result));
    }

    private static Map<String, Object> statusMap() {
        Ss7ResourceAdaptor ra = Ss7AdminBindings.adaptor();
        Ss7RaEndpoint ep = Ss7AdminBindings.endpoint();
        String raName = ep != null ? ep.getRaName() : (ra != null ? "ra-jss7" : null);
        return Ss7LinkStatusSnapshot.capture(ra, raName);
    }
}
