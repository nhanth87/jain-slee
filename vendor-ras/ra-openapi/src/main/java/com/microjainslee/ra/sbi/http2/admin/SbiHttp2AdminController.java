/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http2.admin;

import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.admin.RaAdminJson;
import com.microjainslee.ra.sbi.http2.SbiHttp2ResourceAdaptor;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SbiHttp2AdminController {

    public RaAdminHttpResponse status(RaAdminHttpRequest ignored) {
        return RaAdminJson.ok(statusMap());
    }

    public RaAdminHttpResponse statusHtml(RaAdminHttpRequest ignored) {
        Map<String, Object> st = statusMap();
        boolean listening = Boolean.TRUE.equals(st.get("listening"));
        boolean peer = Boolean.TRUE.equals(st.get("peerTrafficSeen"));
        String badge = peer
                ? "<span class=\"oa-badge oa-badge--peer\">PEER_SEEN</span>"
                : listening
                ? "<span class=\"oa-badge oa-badge--listen\">LISTEN</span>"
                : "<span class=\"oa-badge oa-badge--down\">DOWN</span>";
        String html = "<div class=\"oa-status\" data-plane=\"http2\">"
                + "<div class=\"oa-status__head\"><h3>HTTP/2 · TS 29.500</h3>" + badge + "</div>"
                + "<p class=\"oa-status__hint\">LISTEN is local bind only. PEER_SEEN after ≥1 exchange.</p>"
                + "<div class=\"oa-kv\"><span class=\"oa-k\">host</span><span class=\"oa-v\">"
                + RaAdminJson.escHtml(String.valueOf(st.get("host"))) + "</span></div>"
                + "<div class=\"oa-kv\"><span class=\"oa-k\">port</span><span class=\"oa-v\">"
                + RaAdminJson.escHtml(String.valueOf(st.get("port"))) + "</span></div>"
                + "<div class=\"oa-kv\"><span class=\"oa-k\">catalogOps</span><span class=\"oa-v\">"
                + RaAdminJson.escHtml(String.valueOf(st.get("catalogOps"))) + "</span></div>"
                + "<div class=\"oa-kv\"><span class=\"oa-k\">exchanges</span><span class=\"oa-v\">"
                + RaAdminJson.escHtml(String.valueOf(st.getOrDefault("peerExchanges", 0)))
                + "</span></div></div>";
        return RaAdminHttpResponse.text(200, "text/html; charset=utf-8", html)
                .withHeader("Vary", "HX-Request");
    }

    public RaAdminHttpResponse catalog(RaAdminHttpRequest ignored) {
        SbiHttp2ResourceAdaptor ra = SbiHttp2AdminBindings.adaptor();
        Map<String, Object> out = new LinkedHashMap<>();
        if (ra == null) {
            out.put("ok", false);
            out.put("catalogOps", 0);
            out.put("module", "ra-openapi");
            return RaAdminJson.ok(out);
        }
        out.put("ok", true);
        out.put("module", "ra-openapi");
        out.put("catalogOps", ra.catalog().size());
        out.put("apis", ra.catalog().apiNames());
        out.put("sample", ra.catalog().all().stream().limit(12)
                .map(o -> o.method() + " " + o.pathTemplate() + " → " + o.operationId())
                .toList());
        return RaAdminJson.ok(out);
    }

    public RaAdminHttpResponse getConfig(RaAdminHttpRequest ignored) {
        SbiHttp2ResourceAdaptor ra = SbiHttp2AdminBindings.adaptor();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", ra != null ? ra.host() : SbiHttp2AdminBindings.configuredHost());
        out.put("port", ra != null ? ra.configuredPort() : SbiHttp2AdminBindings.configuredPort());
        out.put("altSvcHttp3", ra != null ? ra.altSvcHttp3() : "");
        out.put("module", "ra-openapi");
        return RaAdminJson.ok(out);
    }

    public RaAdminHttpResponse putConfig(RaAdminHttpRequest req) {
        try {
            var tree = RaAdminJson.mapper().readTree(req == null ? "{}" : req.body());
            String host = tree.hasNonNull("host") ? tree.get("host").asText() : null;
            Integer port = tree.has("port") ? tree.get("port").asInt() : null;
            String alt = tree.hasNonNull("altSvcHttp3") ? tree.get("altSvcHttp3").asText() : null;
            SbiHttp2ResourceAdaptor ra = SbiHttp2AdminBindings.adaptor();
            if (host != null) {
                SbiHttp2AdminBindings.setConfigured(host,
                        port == null ? SbiHttp2AdminBindings.configuredPort() : port);
                if (ra != null) {
                    ra.setHost(host);
                }
            }
            if (port != null) {
                SbiHttp2AdminBindings.setConfigured(SbiHttp2AdminBindings.configuredHost(), port);
                if (ra != null) {
                    ra.setPort(port);
                }
            }
            if (alt != null && ra != null) {
                ra.setAltSvcHttp3(alt);
            }
            return getConfig(req);
        } catch (Exception e) {
            return RaAdminJson.status(400, Map.of("ok", false, "error", e.toString()));
        }
    }

    public RaAdminHttpResponse rebind(RaAdminHttpRequest ignored) {
        SbiHttp2ResourceAdaptor ra = SbiHttp2AdminBindings.adaptor();
        if (ra == null) {
            return RaAdminJson.status(503, Map.of("ok", false, "error", "RA not bound"));
        }
        ra.rebind();
        return RaAdminJson.ok(Map.of("ok", true, "listening", ra.listening()));
    }

    public RaAdminHttpResponse resilience(RaAdminHttpRequest ignored) {
        SbiHttp2ResourceAdaptor ra = SbiHttp2AdminBindings.adaptor();
        Map<String, Object> out = new LinkedHashMap<>();
        if (ra == null) {
            out.put("ok", false);
            return RaAdminJson.ok(out);
        }
        out.put("ok", true);
        out.put("maxRetries", ra.resilience().maxRetries());
        out.put("backoffMs", ra.resilience().backoffMs());
        out.put("peers", ra.resilience().allPeersSnapshot());
        return RaAdminJson.ok(out);
    }

    public RaAdminHttpResponse sagas(RaAdminHttpRequest ignored) {
        SbiHttp2ResourceAdaptor ra = SbiHttp2AdminBindings.adaptor();
        Map<String, Object> out = new LinkedHashMap<>();
        if (ra == null) {
            out.put("ok", false);
            return RaAdminJson.ok(out);
        }
        out.put("ok", true);
        out.put("sagas", ra.sagas().snapshot());
        return RaAdminJson.ok(out);
    }

    private Map<String, Object> statusMap() {
        Map<String, Object> st = new LinkedHashMap<>();
        SbiHttp2ResourceAdaptor ra = SbiHttp2AdminBindings.adaptor();
        if (ra == null) {
            st.put("listening", false);
            st.put("peerTrafficSeen", false);
            st.put("host", SbiHttp2AdminBindings.configuredHost());
            st.put("port", SbiHttp2AdminBindings.configuredPort());
            st.put("catalogOps", 0);
            return st;
        }
        st.put("listening", ra.listening());
        st.put("peerTrafficSeen", ra.peerTrafficSeen());
        st.put("host", ra.host());
        st.put("port", ra.configuredPort());
        st.put("catalogOps", ra.catalog().size());
        st.put("peerExchanges", ra.peerExchangeCount());
        return st;
    }
}
