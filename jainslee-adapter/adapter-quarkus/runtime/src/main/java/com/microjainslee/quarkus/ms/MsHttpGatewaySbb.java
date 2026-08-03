/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus.ms;

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
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.exception.ServiceCallTimeoutException;
import com.microjainslee.ms.api.exception.ServiceUnavailableException;
import com.microjainslee.ms.core.SleeServiceHandlerRegistry;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Generic MS HTTP gateway SBB: {@code ra-http-server} → path dispatch →
 * child {@link IspnMsClientSbb} → {@code ispn-queue-ra} → reply on {@code http-server-ra}.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /api/health}</li>
 *   <li>{@code GET /api/ms/state}</li>
 *   <li>{@code GET /api/ms/handlers} (when {@link MicrosleeMsSupport.MsRuntime#registry()} present)</li>
 *   <li>{@code POST /api/ms/{serviceName}?op=} — sync call; {@code &notify=true} for fire-and-forget</li>
 *   <li>Demo aliases {@code POST /api/demo/call-{ra|aux|sbb}} / {@code notify-*} → http-ra/aux/sbb</li>
 * </ul>
 */
public final class MsHttpGatewaySbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(MsHttpGatewaySbb.class);

    private final Supplier<MicrosleeMsSupport.MsRuntime> runtimeSource;
    private final IspnMsClientSbb ispnChild;

    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort http;

    private volatile SbbLocalObject self;

    public MsHttpGatewaySbb(MicrosleeMsSupport.MsRuntime runtime, IspnMsClientSbb ispnChild) {
        Objects.requireNonNull(runtime, "runtime");
        this.runtimeSource = () -> runtime;
        this.ispnChild = Objects.requireNonNull(ispnChild, "ispnChild");
    }

    /**
     * Lazy source for apps that hold runtime in a CDI bean (ready after boot).
     * Returning {@code null} yields HTTP 503 until ready.
     */
    public MsHttpGatewaySbb(
            Supplier<MicrosleeMsSupport.MsRuntime> runtimeSource,
            IspnMsClientSbb ispnChild) {
        this.runtimeSource = Objects.requireNonNull(runtimeSource, "runtimeSource");
        this.ispnChild = Objects.requireNonNull(ispnChild, "ispnChild");
    }

    /** @deprecated Prefer constructors that take {@link IspnMsClientSbb}. */
    @Deprecated
    public MsHttpGatewaySbb(MicrosleeMsSupport.MsRuntime runtime) {
        this(runtime, new IspnMsClientSbb());
    }

    /** @deprecated Prefer constructors that take {@link IspnMsClientSbb}. */
    @Deprecated
    public MsHttpGatewaySbb(Supplier<MicrosleeMsSupport.MsRuntime> runtimeSource) {
        this(runtimeSource, new IspnMsClientSbb());
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
        Reply reply;
        try {
            reply = dispatch(req);
        } catch (RuntimeException ex) {
            LOG.error("[ms-http-gw] handler failed for {} {}", req.getMethod(), req.getPath(), ex);
            reply = Reply.json(500, "{\"error\":\"internal\"}");
        }
        RaCommandPort port = this.http;
        if (port == null) {
            LOG.warn("[ms-http-gw] no command port injected — dropping response for {}", req.getPath());
            return;
        }
        port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                req.getSessionId(), reply.status(), reply.contentType(),
                reply.text(), reply.binary(), reply.headers()));
    }

    private Reply dispatch(HttpWebRequestEvent req) {
        String method = req.getMethod() == null ? "" : req.getMethod().toUpperCase();
        String path = req.getPath() == null ? "" : req.getPath();

        if ("GET".equals(method) && "/api/health".equals(path)) {
            return health();
        }
        if ("GET".equals(method) && "/api/ms/state".equals(path)) {
            return state();
        }
        if ("GET".equals(method) && "/api/ms/handlers".equals(path)) {
            return handlers();
        }

        if ("POST".equals(method)) {
            DemoAlias alias = demoAlias(path);
            if (alias != null) {
                return invokeMsService(req, alias.serviceName(), alias.notifyOnly());
            }
            if (path.startsWith("/api/ms/")) {
                String serviceName = path.substring("/api/ms/".length());
                if (!serviceName.isBlank() && !serviceName.contains("/")) {
                    boolean notify = "true".equalsIgnoreCase(req.getQueryParam("notify"))
                            || "1".equals(req.getQueryParam("notify"));
                    return invokeMsService(req, serviceName, notify);
                }
            }
        }
        return Reply.notFound();
    }

    private Reply health() {
        MicrosleeMsSupport.MsRuntime rt = currentRuntime();
        if (rt == null) {
            return Reply.json(503, "{\"status\":\"STARTING\"}");
        }
        DeploymentConfig cfg = rt.config();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("mode", cfg.mode().name());
        body.put("nodeId", cfg.myNodeId() == null ? "single" : cfg.myNodeId());
        body.put("local", localFlags(rt));
        body.put("ingress", "ra-http-server→MsHttpGatewaySbb→IspnMsClientSbb→ispn-queue-ra");
        return Reply.json(MsHttpJson.toJson(body));
    }

    private Reply handlers() {
        MicrosleeMsSupport.MsRuntime rt = currentRuntime();
        if (rt == null) {
            return Reply.json(503, "{\"error\":\"STARTING\"}");
        }
        SleeServiceHandlerRegistry registry = rt.registry();
        if (registry == null) {
            return Reply.json(404, "{\"error\":\"no handler registry\"}");
        }
        Map<String, List<String>> bindings = registry.describe();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nn", true);
        out.put("bindings", bindings);
        return Reply.json(MsHttpJson.toJson(out));
    }

    private Reply state() {
        MicrosleeMsSupport.MsRuntime rt = currentRuntime();
        if (rt == null) {
            return Reply.json(503, "{\"error\":\"STARTING\"}");
        }
        Map<String, Object> local = new LinkedHashMap<>();
        for (Map.Entry<String, ServiceState> e : rt.bootstrap().orchestrator().localStates().entrySet()) {
            local.put(e.getKey(), e.getValue().name());
        }
        Map<String, Object> remote = new LinkedHashMap<>();
        for (String name : knownServiceNames(rt)) {
            remote.put(name, ispnChild.queryState(name).name());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("localStates", local);
        out.put("ispnStates", remote);
        SleeServiceHandlerRegistry registry = rt.registry();
        if (registry != null) {
            out.put("handlerBindings", registry.describe());
        }
        return Reply.json(MsHttpJson.toJson(out));
    }

    private Reply invokeMsService(HttpWebRequestEvent req, String serviceName, boolean notifyOnly) {
        MicrosleeMsSupport.MsRuntime rt = currentRuntime();
        if (rt == null) {
            return Reply.json(503, "{\"error\":\"STARTING\"}");
        }
        String op = req.getQueryParam("op");
        if (op == null || op.isBlank()) {
            op = notifyOnly ? "event" : "ping";
        }
        byte[] payload = req.getBody() == null
                ? new byte[0]
                : req.getBody().getBytes(StandardCharsets.UTF_8);

        SleeRequest sleeReq = new SleeRequest(op, payload);
        boolean viaLocal = rt.config().isLocal(serviceName);
        ServiceState remoteState = viaLocal ? null : ispnChild.queryState(serviceName);

        if (notifyOnly) {
            try {
                ispnChild.notify(serviceName, sleeReq);
            } catch (ServiceUnavailableException | ServiceCallTimeoutException ex) {
                LOG.warn("[ms-http-gw] notify {} op={} viaLocal={} failed: {}",
                        serviceName, op, viaLocal, ex.getMessage());
                int status = ex instanceof ServiceCallTimeoutException ? 504 : 503;
                return msError(status, serviceName, op, viaLocal, remoteState, ex.getMessage());
            }
            LOG.info("[ms-http-gw] notify {} op={} viaLocal={}", serviceName, op, viaLocal);
            return Reply.json(MsHttpJson.toJson(Map.of(
                    "notified", true,
                    "op", op,
                    "service", serviceName,
                    "viaLocal", viaLocal)));
        }

        final SleeResponse resp;
        try {
            LOG.info("[ms-http-gw] call {} op={} viaLocal={} remoteState={}",
                    serviceName, op, viaLocal, remoteState == null ? "local" : remoteState);
            resp = ispnChild.call(serviceName, sleeReq);
        } catch (ServiceUnavailableException ex) {
            LOG.warn("[ms-http-gw] call {} op={} viaLocal={} unavailable: {}",
                    serviceName, op, viaLocal, ex.getMessage());
            return msError(503, serviceName, op, viaLocal, remoteState, ex.getMessage());
        } catch (ServiceCallTimeoutException ex) {
            LOG.warn("[ms-http-gw] call {} op={} viaLocal={} timeout: {}",
                    serviceName, op, viaLocal, ex.getMessage());
            return msError(504, serviceName, op, viaLocal, remoteState, ex.getMessage());
        }

        LOG.info("[ms-http-gw] call {} op={} success={} viaLocal={}",
                serviceName, op, resp.success(), viaLocal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", resp.success());
        out.put("payload", new String(resp.payload(), StandardCharsets.UTF_8));
        out.put("error", resp.errorMessage() == null ? "" : resp.errorMessage());
        out.put("service", serviceName);
        out.put("viaLocal", viaLocal);
        if (remoteState != null) {
            out.put("remoteState", remoteState.name());
        }
        return resp.success()
                ? Reply.json(MsHttpJson.toJson(out))
                : Reply.json(502, MsHttpJson.toJson(out));
    }

    private MicrosleeMsSupport.MsRuntime currentRuntime() {
        try {
            return runtimeSource.get();
        } catch (RuntimeException ex) {
            LOG.debug("[ms-http-gw] runtime not ready: {}", ex.toString());
            return null;
        }
    }

    private static Map<String, Object> localFlags(MicrosleeMsSupport.MsRuntime rt) {
        DeploymentConfig cfg = rt.config();
        Map<String, Object> local = new LinkedHashMap<>();
        for (String name : knownServiceNames(rt)) {
            local.put(name, cfg.isLocal(name));
        }
        return local;
    }

    private static Set<String> knownServiceNames(MicrosleeMsSupport.MsRuntime rt) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        List<SleeServiceDescriptor> descriptors = rt.descriptors();
        if (descriptors != null) {
            for (SleeServiceDescriptor d : descriptors) {
                names.add(d.name());
            }
        }
        names.addAll(rt.config().services().keySet());
        names.addAll(rt.bootstrap().orchestrator().localStates().keySet());
        return names;
    }

    private static Reply msError(
            int status,
            String serviceName,
            String op,
            boolean viaLocal,
            ServiceState remoteState,
            String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("payload", "");
        out.put("error", error == null ? "error" : error);
        out.put("service", serviceName);
        out.put("op", op);
        out.put("viaLocal", viaLocal);
        if (remoteState != null) {
            out.put("remoteState", remoteState.name());
        }
        return Reply.json(status, MsHttpJson.toJson(out));
    }

    private static DemoAlias demoAlias(String path) {
        return switch (path) {
            case "/api/demo/call-ra", "/api/demo/call-signaling" -> new DemoAlias("http-ra", false);
            case "/api/demo/notify-ra", "/api/demo/notify-signaling" -> new DemoAlias("http-ra", true);
            case "/api/demo/call-aux" -> new DemoAlias("http-aux", false);
            case "/api/demo/notify-aux" -> new DemoAlias("http-aux", true);
            case "/api/demo/call-sbb" -> new DemoAlias("http-sbb", false);
            case "/api/demo/notify-sbb" -> new DemoAlias("http-sbb", true);
            default -> null;
        };
    }

    private record DemoAlias(String serviceName, boolean notifyOnly) {
    }

    private record Reply(int status, String contentType, String text, byte[] binary,
                         Map<String, String> headers) {

        static Reply json(String body) {
            return new Reply(200, "application/json", body, null, Map.of());
        }

        static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, null, Map.of());
        }

        static Reply notFound() {
            return new Reply(404, "text/plain; charset=utf-8", "Not found", null, Map.of());
        }
    }
}
