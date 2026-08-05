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

import com.microjainslee.admin.RaAdminApiRegistrar;
import com.microjainslee.admin.RaAdminDashboardContributor;
import com.microjainslee.admin.RaAdminManifest;

public final class SbiHttp3RaAdminContributor implements RaAdminDashboardContributor {
    private final SbiHttp3AdminController controller = new SbiHttp3AdminController();

    @Override
    public RaAdminManifest manifest() {
        return RaAdminManifest.of("sbi-http3-ra", "openapi", "OpenAPI HTTP/3", 26);
    }

    @Override
    public void registerApis(RaAdminApiRegistrar registrar) {
        registrar.get("/status", controller::status);
        registrar.get("/status.html", controller::statusHtml);
        registrar.get("/catalog", controller::catalog);
        registrar.post("/rebind", controller::rebind);
        registrar.get("/resilience", controller::resilience);
        registrar.get("/sagas", controller::sagas);
    }
}
