/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.authz;

/**
 * Default role names of the three-level multi-tenant model
 * {@code PLATFORM_ADMIN > TENANT_ADMIN > USER}. Applications may use their
 * own names — see {@link RoleHierarchy#parse(String)}.
 */
public final class Roles {

    /** Operates the whole platform; the only role allowed across tenants by default. */
    public static final String PLATFORM_ADMIN = "platform-admin";
    /** Administers one tenant (company): its users, configuration, billing. */
    public static final String TENANT_ADMIN = "tenant-admin";
    /** Regular member of one tenant. */
    public static final String USER = "user";

    private Roles() {
    }
}
