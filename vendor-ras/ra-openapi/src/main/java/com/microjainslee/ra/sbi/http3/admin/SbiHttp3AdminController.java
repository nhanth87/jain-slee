/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http3.admin;

import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.admin.RaAdminJson;
import com.microjainslee.ra.sbi.http3.SbiHttp3ResourceAdaptor;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SbiHttp3AdminController {

    public RaAdminHttpResponse status(RaAdminHttpRequest ignored) {
        return RaAdminJson.ok(statusMap());
    }

    public RaAdminHttpResponse statusHtml(RaAdminHttpRequest ignored) {
        Map<String, Object> st = statusMap();
        boolean quic = Boolean.TRUE.equals(st.get("quicReady"));
        boolean peer = Boolean.TRUE.equals(st.get("peerTrafficSeen"));
        boolean listening = Boolean.TRUE.equals(st.get("listening"));
        // Honesty: Quic LIVE only when quicReady; PEER_SEEN is traffic; LISTEN ≠ peer UP.
        String badge;
        if (quic) {
            badge = "<span class=\"oa-badge oa-badge--quic\">QUIC</span>";
        } else if (peer) {
            badge = "<span class=\"oa-badge oa-badge--peer\">PEER_SEEN</span>";
        } else if (listening) {
            badge = "<span class=\"oa-badge oa-badge--tcp\">TCP_FALLBACK</span>";
        } else {
            badge = "<span class=\"oa-badge oa-badge--down\">DOWN</span>";
        }
        String html = "<div class=\"oa-status\" data-plane=\"http3\">"
                + "<div class=\"oa-status__head\"><h3>HTTP/3 · experimental</h3>" + badge + "</div>"
                + "<p class=\"oa-status__hint\">QUIC = HTTP/3 path ready. TCP_FALLBACK = Vert.x 4 cleartext. "
                + "LISTEN ≠ peer UP.</p>"
                + "<div class=\"oa-kv\"><span class=\"oa-k\">tcpPort</span><span class=\"oa-v\">"
                + RaAdminJson.escHtml(String.valueOf(st.get("tcpPort"))) + "</span></div>"
                + "<div class=\"oa-kv\"><span class=\"oa-k\">quicPort</span><span class=\"oa-v\">"
                + RaAdminJson.escHtml(String.valueOf(st.get("quicPort"))) + "</span></div>"
                + "<div class=\"oa-kv\"><span class=\"oa-k\">quicError</span><span class=\"oa-v\">"
                + RaAdminJson.escHtml(String.valueOf(st.getOrDefault("quicError", "")))
                + "</span></div>"
                + "<div class=\"oa-kv\"><span class=\"oa-k\">catalogOps</span><span class=\"oa-v\">"
                + RaAdminJson.escHtml(String.valueOf(st.getOrDefault("catalogOps", 0)))
                + "</span></div></div>";
        return RaAdminHttpResponse.text(200, "text/html; charset=utf-8", html)
                .withHeader("Vary", "HX-Request");
    }

    public RaAdminHttpResponse catalog(RaAdminHttpRequest ignored) {
        SbiHttp3ResourceAdaptor ra = SbiHttp3AdminBindings.adaptor();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ra != null);
        out.put("catalogOps", ra == null ? 0 : ra.catalog().size());
        out.put("module", "ra-openapi");
        return RaAdminJson.ok(out);
    }

    public RaAdminHttpResponse rebind(RaAdminHttpRequest ignored) {
        SbiHttp3ResourceAdaptor ra = SbiHttp3AdminBindings.adaptor();
        if (ra == null) {
            return RaAdminJson.status(503, Map.of("ok", false, "error", "RA not bound"));
        }
        ra.rebind();
        return RaAdminJson.ok(Map.of(
                "ok", true,
                "listening", ra.listening(),
                "quicReady", ra.quicReady(),
                "peerTrafficSeen", ra.peerTrafficSeen()));
    }

    public RaAdminHttpResponse resilience(RaAdminHttpRequest ignored) {
        SbiHttp3ResourceAdaptor ra = SbiHttp3AdminBindings.adaptor();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ra != null);
        if (ra != null) {
            out.put("peers", ra.resilience().allPeersSnapshot());
        }
        return RaAdminJson.ok(out);
    }

    public RaAdminHttpResponse sagas(RaAdminHttpRequest ignored) {
        SbiHttp3ResourceAdaptor ra = SbiHttp3AdminBindings.adaptor();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ra != null);
        if (ra != null) {
            out.put("sagas", ra.sagas().snapshot());
        }
        return RaAdminJson.ok(out);
    }

    private Map<String, Object> statusMap() {
        Map<String, Object> st = new LinkedHashMap<>();
        SbiHttp3ResourceAdaptor ra = SbiHttp3AdminBindings.adaptor();
        if (ra == null) {
            st.put("listening", false);
            st.put("quicReady", false);
            st.put("peerTrafficSeen", false);
            st.put("tcpPort", SbiHttp3AdminBindings.configuredTcpPort());
            st.put("quicPort", SbiHttp3AdminBindings.configuredQuicPort());
            st.put("catalogOps", 0);
            return st;
        }
        st.put("listening", ra.listening());
        st.put("quicReady", ra.quicReady());
        st.put("peerTrafficSeen", ra.peerTrafficSeen());
        st.put("tcpPort", ra.tcpPort());
        st.put("quicPort", ra.quicPort());
        st.put("quicError", ra.quicError());
        st.put("catalogOps", ra.catalog().size());
        return st;
    }
}
