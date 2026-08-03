/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.sbbs;

import com.example.ms.quarkus.bootstrap.MsRuntimeHolder;
import com.example.ms.quarkus.handlers.ServiceHandlers;
import com.example.ms.quarkus.http.HttpReply;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single HTTP gateway SBB for the MS demo. {@code ra-http-server} fires
 * {@link HttpWebRequestEvent}; this SBB dispatches by path, uses
 * {@link MicrosleeBootstrap#client} for demo signaling ops, and replies on the
 * injected {@code http-server-ra} command port.
 *
 * <p>Call/notify are done inline (not via nested {@code routeEvent}+wait) so the
 * EventRouter worker is never blocked. Sibling {@link MsAppBridgeSbb} owns the
 * local {@code MsServiceCallEvent} path for SBB-to-SBB MS invocations.</p>
 */
public final class MsGatewaySbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(MsGatewaySbb.class);

    private final MsRuntimeHolder runtimeHolder;

    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort http;

    private volatile SbbLocalObject self;

    public MsGatewaySbb(MsRuntimeHolder runtimeHolder) {
        this.runtimeHolder = runtimeHolder;
    }

    public void bindSelf(SbbLocalObject self) {
        this.self = self;
    }

    @Override
    public void sbbCreate() {
    }

    @Override
    public void sbbActivate() {
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof HttpWebRequestEvent req)) {
            return;
        }
        HttpReply reply;
        try {
            reply = dispatch(req);
        } catch (RuntimeException ex) {
            LOG.error("[gateway] handler failed for {} {}", req.getMethod(), req.getPath(), ex);
            reply = HttpReply.json(500, "{\"error\":\"internal\"}");
        }
        RaCommandPort port = this.http;
        if (port == null) {
            LOG.warn("[gateway] no command port injected — dropping response for {}", req.getPath());
            return;
        }
        port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                req.getSessionId(), reply.status(), reply.contentType(),
                reply.text(), reply.binary(), reply.headers()));
    }

    private HttpReply dispatch(HttpWebRequestEvent req) {
        String method = req.getMethod() == null ? "" : req.getMethod().toUpperCase();
        String path = req.getPath() == null ? "" : req.getPath();

        if ("GET".equals(method) && "/api/health".equals(path)) {
            return health();
        }
        if ("GET".equals(method) && "/api/ms/state".equals(path)) {
            return state();
        }
        if ("POST".equals(method) && "/api/demo/call-signaling".equals(path)) {
            return invokeSignaling(req, false);
        }
        if ("POST".equals(method) && "/api/demo/notify-signaling".equals(path)) {
            return invokeSignaling(req, true);
        }
        return HttpReply.notFound();
    }

    private HttpReply health() {
        if (!runtimeHolder.isReady()) {
            return HttpReply.json(503, "{\"status\":\"STARTING\"}");
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
        return HttpReply.json(toJson(body));
    }

    private HttpReply state() {
        if (!runtimeHolder.isReady()) {
            return HttpReply.json(503, "{\"error\":\"STARTING\"}");
        }
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
        return HttpReply.json(toJson(out));
    }

    private HttpReply invokeSignaling(HttpWebRequestEvent req, boolean notifyOnly) {
        if (!runtimeHolder.isReady()) {
            return HttpReply.json(503, "{\"error\":\"STARTING\"}");
        }
        String op = req.getQueryParam("op");
        if (op == null || op.isBlank()) {
            op = notifyOnly ? "event" : "ping";
        }
        byte[] payload = req.getBody() == null
                ? new byte[0]
                : req.getBody().getBytes(StandardCharsets.UTF_8);

        MicrosleeBootstrap boot = runtimeHolder.get().bootstrap();
        SleeRequest sleeReq = new SleeRequest(op, payload);

        if (notifyOnly) {
            boot.client("signaling").notify(sleeReq);
            LOG.info("[gateway] notify signaling op={}", op);
            return HttpReply.json(toJson(Map.of("notified", true, "op", op)));
        }

        SleeResponse resp = boot.client("signaling").call(sleeReq);
        LOG.info("[gateway] call signaling op={} success={}", op, resp.success());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", resp.success());
        out.put("payload", new String(resp.payload(), StandardCharsets.UTF_8));
        out.put("error", resp.errorMessage() == null ? "" : resp.errorMessage());
        out.put("viaLocal", runtimeHolder.get().config().isLocal("signaling"));
        return resp.success()
                ? HttpReply.json(toJson(out))
                : HttpReply.json(502, toJson(out));
    }

    @SuppressWarnings("unchecked")
    static String toJson(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder(128).append('{');
        boolean first = true;
        for (Map.Entry<String, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Boolean || v instanceof Number) {
                sb.append(v);
            } else if (v instanceof Map<?, ?> nested) {
                sb.append(toJson((Map<String, ?>) nested));
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
