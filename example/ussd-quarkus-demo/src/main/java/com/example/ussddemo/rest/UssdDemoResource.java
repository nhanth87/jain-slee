/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.rest;

import com.example.ussddemo.service.UssdSessionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * HTTP entry point that simulates the USSD gateway firing SS7 USSD events into micro-jainslee.
 */
@Path("/api/ussd")
@Produces(MediaType.APPLICATION_JSON)
public final class UssdDemoResource {

    @Inject
    UssdSessionService sessionService;

    @GET
    @Path("/health")
    public String health() {
        return "ok";
    }

    @POST
    @Path("/begin")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response begin(UssdBeginRequest request) {
        UssdSessionView view = sessionService.begin(request);
        return Response.accepted(view).build();
    }

    @GET
    @Path("/sessions/{sessionId}")
    public UssdSessionView session(@PathParam("sessionId") String sessionId) {
        UssdSessionView view = sessionService.getSession(sessionId);
        if (view == null) {
            throw new NotFoundException("Unknown session: " + sessionId);
        }
        return view;
    }
}
