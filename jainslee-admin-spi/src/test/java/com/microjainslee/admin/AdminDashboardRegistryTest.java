/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.admin;

import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdminDashboardRegistryTest {

    @Test
    public void loadsFakeContributorSortedAndDispatches() {
        RaAdminDashboardContributor late = new FakeContributor("ra-b", "b", 20);
        RaAdminDashboardContributor early = new FakeContributor("ra-a", "a", 10);
        AdminDashboardRegistry reg = AdminDashboardRegistry.of(late, early);

        List<RaAdminManifest> manifests = reg.manifests();
        assertEquals(2, manifests.size());
        assertEquals("ra-a", manifests.get(0).raName());
        assertEquals("ra-b", manifests.get(1).raName());

        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/ra-a/status", null));
        assertTrue(hit.isPresent());
        assertEquals(200, hit.get().status());
        assertTrue(hit.get().bodyAsString().contains("\"raName\":\"ra-a\""));

        assertFalse(reg.dispatch(
                RaAdminHttpRequest.of("GET", "/api/ra/missing/status", null)).isPresent());
    }

    @Test
    public void postHandlerReachable() {
        AdminDashboardRegistry reg = AdminDashboardRegistry.of(
                new FakeContributor("http-server-ra", "http", 5));
        Optional<RaAdminHttpResponse> hit = reg.dispatch(
                RaAdminHttpRequest.of("POST", "/api/ra/http-server-ra/echo", "{\"x\":1}"));
        assertTrue(hit.isPresent());
        assertTrue(hit.get().bodyAsString().contains("{\"x\":1}"));
    }

    private static final class FakeContributor implements RaAdminDashboardContributor {
        private final RaAdminManifest manifest;

        FakeContributor(String raName, String tabId, int order) {
            this.manifest = RaAdminManifest.of(raName, tabId, raName.toUpperCase(), order);
        }

        @Override
        public RaAdminManifest manifest() {
            return manifest;
        }

        @Override
        public void registerApis(RaAdminApiRegistrar registrar) {
            registrar.get("/status", req ->
                    RaAdminHttpResponse.json(
                            "{\"raName\":\"" + manifest.raName() + "\",\"ok\":true}"));
            registrar.post("/echo", req ->
                    RaAdminHttpResponse.json(req.body() == null ? "{}" : req.body()));
        }
    }
}
