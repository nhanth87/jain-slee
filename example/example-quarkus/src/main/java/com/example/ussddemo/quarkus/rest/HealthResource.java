/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Optional Quarkus health — USSD traffic uses HTTP RA on ussd.http.port. */
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public final class HealthResource {

    @GET
    public String health() {
        return "{\"status\":\"ok\",\"note\":\"USSD via HTTP RA\"}";
    }
}
