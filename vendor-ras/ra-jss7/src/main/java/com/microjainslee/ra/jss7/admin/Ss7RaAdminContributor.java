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

import com.microjainslee.admin.RaAdminApiRegistrar;
import com.microjainslee.admin.RaAdminDashboardContributor;
import com.microjainslee.admin.RaAdminManifest;

/**
 * ServiceLoader contributor for the ra-jss7 admin tab in jainslee-monitor.
 */
public final class Ss7RaAdminContributor implements RaAdminDashboardContributor {

    private final Ss7AdminController controller = new Ss7AdminController();

    @Override
    public RaAdminManifest manifest() {
        return RaAdminManifest.of("ra-jss7", "ss7", "SS7", 10);
    }

    @Override
    public void registerApis(RaAdminApiRegistrar registrar) {
        registrar.get("/status", controller::status);
        registrar.get("/status.html", controller::statusHtml);
        registrar.get("/config", controller::config);
        registrar.post("/validate", controller::validate);
        registrar.post("/apply", controller::apply);
        registrar.post("/start", controller::start);
        registrar.post("/stop", controller::stop);
    }
}
