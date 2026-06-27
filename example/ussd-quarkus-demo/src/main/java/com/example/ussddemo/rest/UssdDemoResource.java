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
import jakarta.ws.rs.QueryParam;
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
        UssdSessionView view = sessionService.begin(request, null);
        return Response.accepted(view).build();
    }

    /**
     * HttpClient RA-style begin: returns 202 Accepted with a {@code Location}
     * header pointing at the per-session callback URL. When the SLEE pipeline
     * finishes, the server POSTs the {@link UssdSessionView} body to that URL
     * — equivalent to Mobicents' {@code HttpClientSbb.execute()} callback flow.
     */
    @POST
    @Path("/begin-callback")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response beginCallback(UssdBeginRequest request,
                                  @QueryParam("callbackUrl") String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("callbackUrl is required")
                    .build();
        }
        UssdSessionView view = sessionService.begin(request, callbackUrl);
        return Response.accepted(view)
                .header("Location", callbackUrl + "?sessionId=" + view.sessionId)
                .build();
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
