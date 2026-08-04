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
 * Registers HTTP handlers for one RA admin pack. Paths are suffixes under
 * the pack's {@link RaAdminManifest#apiBase()} (e.g. {@code /status} →
 * {@code /api/ra/ra-jss7/status}).
 */
public interface RaAdminApiRegistrar {

    @FunctionalInterface
    interface Handler {
        RaAdminHttpResponse handle(RaAdminHttpRequest request);
    }

    void get(String pathSuffix, Handler handler);

    void post(String pathSuffix, Handler handler);

    void put(String pathSuffix, Handler handler);

    void delete(String pathSuffix, Handler handler);
}
