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

import com.example.ussddemo.quarkus.quarkus.UssdDemoRuntime;
import com.example.ussddemo.quarkus.service.UssdSessionStore;
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

import java.util.UUID;

/**
 * Quarkus REST entry point. The {@link MicroSleeContainer} is injected
 * via the adapter-quarkus CDI extension; this resource does NOT
 * construct a container, NOT register SBBs manually outside of the
 * per-session flow.
 */
@Path("/api/ussd")
@Produces(MediaType.APPLICATION_JSON)
public final class UssdDemoResource {

    @Inject
    UssdDemoRuntime runtime;

    @Inject
    UssdSessionStore sessionStore;

    @GET
    @Path("/health")
    public String health() {
        return "ok";
    }

    @POST
    @Path("/begin")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response begin(UssdBeginRequest request) {
        validate(request);
        String sessionId = UUID.randomUUID().toString();
        runtime.beginSession(sessionId, request.msisdn, request.ussdString, null);
        return Response.accepted(UssdSessionView.processing(sessionId)).build();
    }

    /**
     * HttpClient RA-style begin: returns 202 Accepted with a {@code Location}
     * header pointing at the per-session callback URL. When the SLEE pipeline
     * finishes, the server POSTs the {@link UssdSessionView} body to that URL.
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
        validate(request);
        String sessionId = UUID.randomUUID().toString();
        runtime.beginSession(sessionId, request.msisdn, request.ussdString, callbackUrl);
        return Response.accepted(UssdSessionView.processing(sessionId))
                .header("Location", callbackUrl + "?sessionId=" + sessionId)
                .build();
    }

    @GET
    @Path("/sessions/{sessionId}")
    public UssdSessionView session(@PathParam("sessionId") String sessionId) {
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record == null) {
            throw new NotFoundException("Unknown session: " + sessionId);
        }
        UssdSessionView view = new UssdSessionView();
        view.sessionId = sessionId;
        view.status = record.getStatus().name();
        view.responseText = record.getResponseText();
        view.errorMessage = record.getErrorMessage();
        return view;
    }

    private static void validate(UssdBeginRequest request) {
        if (request == null || request.msisdn == null || request.msisdn.trim().isEmpty()) {
            throw new IllegalArgumentException("msisdn is required");
        }
        if (request.ussdString == null || request.ussdString.trim().isEmpty()) {
            throw new IllegalArgumentException("ussdString is required");
        }
    }
}
