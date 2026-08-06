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

import com.microjainslee.admin.AdminDashboardRegistry;
import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import org.junit.After;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpServerRaAdminSmokeTest {

    @After
    public void tearDown() {
        HttpServerAdminBindings.clear();
        HttpServerAdminBindings.clearAppPanels();
    }

    @Test
    public void statusReportsLocalListenOnly() {
        AdminDashboardRegistry reg =
                AdminDashboardRegistry.of(new HttpServerRaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/http-server-ra/status", null));
        assertTrue(hit.isPresent());
        String body = hit.get().bodyAsString();
        assertTrue(body.contains("\"active\":false"));
        assertTrue(body.contains("\"listening\":false"));
        assertTrue(body.contains("LISTEN") || body.contains("listen") || body.contains("down"));
        // No peer plane invented
        assertFalse(body.contains("\"peerReady\""));
        assertTrue(body.contains("configuredHost"));
        assertTrue(body.contains("configuredPort"));
        assertTrue(body.contains("\"detail\""));
    }

    @Test
    public void statusHtmlFragment() {
        AdminDashboardRegistry reg =
                AdminDashboardRegistry.of(new HttpServerRaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/http-server-ra/status.html", null));
        assertTrue(hit.isPresent());
        assertEquals(200, hit.get().status());
        assertTrue(hit.get().bodyAsString().contains("link-status-panel"));
    }

    @Test
    public void configRoundTripWithoutBoundRa() {
        HttpServerAdminController ctrl = new HttpServerAdminController();
        RaAdminHttpResponse put = ctrl.putConfig(
                RaAdminHttpRequest.of("POST", "/api/ra/http-server-ra/config",
                        "{\"host\":\"0.0.0.0\",\"port\":9099}"));
        assertEquals(200, put.status());
        assertTrue(put.bodyAsString().contains("0.0.0.0"));
        assertTrue(put.bodyAsString().contains("9099"));

        RaAdminHttpResponse get = ctrl.getConfig(null);
        assertTrue(get.bodyAsString().contains("9099"));
    }

    @Test
    public void boundInactiveAdaptorStatus() {
        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setHost("127.0.0.1");
        ra.setPort(0);
        HttpServerAdminBindings.bind(ra);
        assertFalse(ra.isActive());
        RaAdminHttpResponse st = new HttpServerAdminController().status(null);
        String body = st.bodyAsString();
        assertTrue(body.contains("\"active\":false"));
        assertTrue(body.contains("\"listening\":false"));
        assertTrue(body.contains("\"bound\":true"));
        // Green tab semantics: listening mirrors active (comment for ADR 0003)
        assertEquals("http", new HttpServerRaAdminContributor().manifest().tabId());
    }

    @Test
    public void endpointsJsonListsRaRoutes() {
        AdminDashboardRegistry reg =
                AdminDashboardRegistry.of(new HttpServerRaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/http-server-ra/endpoints", null));
        assertTrue(hit.isPresent());
        String body = hit.get().bodyAsString();
        assertTrue(body.contains("\"ok\":true"));
        assertTrue(body.contains("/health"));
        assertTrue(body.contains("http-server-ra"));
        assertTrue(body.contains("LISTEN"));
        assertFalse(body.contains("\"peerReady\""));
    }

    @Test
    public void endpointsHtmlEscapesAndTables() {
        AdminDashboardRegistry reg =
                AdminDashboardRegistry.of(new HttpServerRaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/http-server-ra/endpoints.html", null));
        assertTrue(hit.isPresent());
        assertEquals(200, hit.get().status());
        String html = hit.get().bodyAsString();
        assertTrue(html.contains("link-status-table--endpoints"));
        assertTrue(html.contains("/health"));
        assertTrue(html.contains("<th>Method</th>"));
    }

    @Test
    public void endpointsHtmlEscapesXss() {
        com.microjainslee.admin.HttpEndpointCatalog.shared().replace(
                "xss-test",
                java.util.List.of(com.microjainslee.admin.HttpEndpointInfo.of(
                        "GET", "/<script>x</script>", "app", "a<b>")));
        try {
            RaAdminHttpResponse html = new HttpServerAdminController().endpointsHtml(null);
            String body = html.bodyAsString();
            assertFalse(body.contains("<script>x</script>"));
            assertTrue(body.contains("&lt;script&gt;"));
            assertTrue(body.contains("a&lt;b&gt;"));
        } finally {
            com.microjainslee.admin.HttpEndpointCatalog.shared().clear("xss-test");
        }
    }

    @Test
    public void ussdPanelUnboundReturnsStub() {
        AdminDashboardRegistry reg =
                AdminDashboardRegistry.of(new HttpServerRaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/http-server-ra/ussd/sync.html", null));
        assertTrue(hit.isPresent());
        assertEquals(200, hit.get().status());
        assertTrue(hit.get().bodyAsString().contains("No app panel bound"));
    }

    @Test
    public void ussdPanelBoundHookReturnsFragment() {
        HttpServerAdminBindings.bindAppPanels(
                (panel, req) -> RaAdminHttpResponse.text(200, "text/html; charset=utf-8",
                        "<div class=\"sync-panel\">hook-" + panel + "</div>"),
                (panel, req) -> RaAdminHttpResponse.text(200, "text/html; charset=utf-8",
                        "<div class=\"sync-panel\">posted-" + panel + "</div>"));
        AdminDashboardRegistry reg =
                AdminDashboardRegistry.of(new HttpServerRaAdminContributor());
        Optional<RaAdminHttpResponse> get = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/http-server-ra/ussd/async.html", null));
        assertTrue(get.isPresent());
        assertTrue(get.get().bodyAsString().contains("hook-async"));

        Optional<RaAdminHttpResponse> post = reg.dispatch(
                RaAdminHttpRequest.of("POST", "/api/ra/http-server-ra/ussd/callback.html",
                        "action=lab"));
        assertTrue(post.isPresent());
        assertTrue(post.get().bodyAsString().contains("posted-callback"));
    }
}
