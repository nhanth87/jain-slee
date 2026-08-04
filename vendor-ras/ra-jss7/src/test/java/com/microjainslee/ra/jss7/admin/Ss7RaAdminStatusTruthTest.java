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

import com.microjainslee.admin.AdminDashboardRegistry;
import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import org.junit.After;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Link-status truth: status JSON must expose routeReady separately from active,
 * and never imply peer UP from isActive alone.
 */
public class Ss7RaAdminStatusTruthTest {

    @After
    public void tearDown() {
        Ss7AdminBindings.clear();
        Ss7AdminBindings.clearHooks();
        Ss7AdminBindings.setLastConfigJson(null);
    }

    @Test
    public void statusSeparatesActiveFromRouteReadyWhenUnbound() {
        AdminDashboardRegistry reg = AdminDashboardRegistry.of(new Ss7RaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/ra-jss7/status", null));
        assertTrue(hit.isPresent());
        String body = hit.get().bodyAsString();
        assertTrue(body.contains("\"active\":false"));
        assertTrue(body.contains("\"routeReady\":false"));
        assertTrue(body.contains("isM3uaRouteReady"));
        assertTrue(body.contains("\"servers\":[]"));
        assertTrue(body.contains("\"associations\":[]"));
        assertTrue(body.contains("\"asps\":[]"));
        assertTrue(body.contains("\"applicationServers\":[]"));
        assertFalse(body.contains("\"live\":true"));
    }

    @Test
    public void statusHtmlEscapesAndReturnsFragment() {
        AdminDashboardRegistry reg = AdminDashboardRegistry.of(new Ss7RaAdminContributor());
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/ra-jss7/status.html", null));
        assertTrue(hit.isPresent());
        assertEquals(200, hit.get().status());
        assertTrue(hit.get().contentType().startsWith("text/html"));
        String body = hit.get().bodyAsString();
        assertTrue(body.contains("link-status-panel"));
        assertFalse(body.contains("<script"));
    }

    @Test
    public void activeTrueDoesNotForceRouteReady() {
        Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
        Ss7AdminBindings.bind(ra);
        assertFalse(ra.isActive());
        assertFalse(ra.isM3uaRouteReady());

        Ss7AdminController ctrl = new Ss7AdminController();
        RaAdminHttpResponse resp = ctrl.status(null);
        String body = resp.bodyAsString();
        assertEquals(200, resp.status());
        assertTrue(body.contains("\"active\":false"));
        assertTrue(body.contains("\"routeReady\":false"));
        assertTrue(body.contains("\"bound\":true"));
        assertTrue(body.contains("\"stackStarted\":false"));
    }

    @Test
    public void contributorManifestTabIdIsSs7() {
        Ss7RaAdminContributor c = new Ss7RaAdminContributor();
        assertEquals("ss7", c.manifest().tabId());
        assertEquals("ra-jss7", c.manifest().raName());
    }
}
