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

import com.microjainslee.admin.RaAdminApiRegistrar;
import com.microjainslee.admin.RaAdminDashboardContributor;
import com.microjainslee.admin.RaAdminManifest;

public final class HttpServerRaAdminContributor implements RaAdminDashboardContributor {

    private final HttpServerAdminController controller = new HttpServerAdminController();

    @Override
    public RaAdminManifest manifest() {
        // statusDotHint amber when active is applied by the panel JS
        return RaAdminManifest.of("http-server-ra", "http", "HTTP Server", 20);
    }

    @Override
    public void registerApis(RaAdminApiRegistrar registrar) {
        registrar.get("/status", controller::status);
        registrar.get("/status.html", controller::statusHtml);
        registrar.get("/endpoints", controller::endpoints);
        registrar.get("/endpoints.html", controller::endpointsHtml);
        registrar.get("/config", controller::getConfig);
        registrar.post("/config", controller::putConfig);
        registrar.post("/rebind", controller::rebind);
    }
}
