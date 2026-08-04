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

/**
 * ServiceLoader SPI: one contributor per RA admin pack.
 *
 * <p>Implementations live in vendor RA jars or app modules and are discovered
 * via {@code META-INF/services/com.microjainslee.admin.RaAdminDashboardContributor}.</p>
 */
public interface RaAdminDashboardContributor {

    RaAdminManifest manifest();

    void registerApis(RaAdminApiRegistrar registrar);
}
