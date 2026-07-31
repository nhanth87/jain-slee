/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.api;

import com.example.ms.quarkus.bootstrap.MsRuntimeHolder;
import com.example.ms.quarkus.handlers.ServiceHandlers;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo REST surface so operators can exercise Direct / ISPN service calls
 * without writing client code.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class MsDemoResource {

    @Inject
    MsRuntimeHolder runtimeHolder;

    @GET
    @Path("/health")
    public Response health() {
        if (!runtimeHolder.isReady()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("status", "STARTING"))
                    .build();
        }
        MicrosleeMsSupport.MsRuntime rt = runtimeHolder.get();
        DeploymentConfig cfg = rt.config();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("mode", cfg.mode().name());
        body.put("nodeId", cfg.myNodeId() == null ? "single" : cfg.myNodeId());
        body.put("local", Map.of(
                "signaling", cfg.isLocal("signaling"),
                "app", cfg.isLocal("app")));
        return Response.ok(body).build();
    }

    @GET
    @Path("/ms/state")
    public Map<String, Object> state() {
        MicrosleeMsSupport.MsRuntime rt = runtimeHolder.get();
        Map<String, Object> local = new LinkedHashMap<>();
        for (Map.Entry<String, ServiceState> e : rt.bootstrap().orchestrator().localStates().entrySet()) {
            local.put(e.getKey(), e.getValue().name());
        }
        Map<String, Object> remote = new LinkedHashMap<>();
        for (String name : new String[]{"signaling", "app"}) {
            remote.put(name, rt.transport().stateOf(name).name());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("localStates", local);
        out.put("ispnStates", remote);
        out.put("counters", Map.of(
                "signalingCalls", ServiceHandlers.signalingCalls(),
                "appCalls", ServiceHandlers.appCalls()));
        return out;
    }

    /**
     * Call the {@code signaling} service through {@link MicrosleeBootstrap#client}.
     * Transport is transparent: Direct when local, Infinispan queue when remote.
     */
    @POST
    @Path("/demo/call-signaling")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response callSignaling(
            @QueryParam("op") @DefaultValue("ping") String op,
            String body) {
        MicrosleeBootstrap boot = runtimeHolder.get().bootstrap();
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        SleeResponse resp = boot.client("signaling").call(new SleeRequest(op, payload));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", resp.success());
        out.put("payload", new String(resp.payload(), StandardCharsets.UTF_8));
        out.put("error", resp.errorMessage());
        out.put("viaLocal", runtimeHolder.get().config().isLocal("signaling"));
        return resp.success() ? Response.ok(out).build()
                : Response.status(Response.Status.BAD_GATEWAY).entity(out).build();
    }

    @POST
    @Path("/demo/notify-signaling")
    @Consumes(MediaType.TEXT_PLAIN)
    public Map<String, Object> notifySignaling(
            @QueryParam("op") @DefaultValue("event") String op,
            String body) {
        MicrosleeBootstrap boot = runtimeHolder.get().bootstrap();
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        boot.client("signaling").notify(new SleeRequest(op, payload));
        return Map.of("notified", true, "op", op);
    }
}
