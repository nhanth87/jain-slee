/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.sbbs;

import com.example.ussddemo.spring.events.GrpcBackendRequestEvent;
import com.example.ussddemo.spring.events.GrpcBackendResponseEvent;
import com.example.ussddemo.spring.grpc.MockGrpcMenuClient;
import com.example.ussddemo.spring.service.UssdDemoRuntime;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.SbbAnnotation;
import org.jboss.logging.Logger;

@SbbAnnotation(name = "GrpcBackend", vendor = "com.example.ussddemo", version = "1.0")
public final class GrpcBackendSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = Logger.getLogger(GrpcBackendSbb.class);

    private UssdDemoRuntime runtime;
    private MockGrpcMenuClient grpcClient;

    public GrpcBackendSbb() {
    }

    public GrpcBackendSbb(UssdDemoRuntime runtime) {
        this.runtime = runtime;
    }

    /** Spring setter for the wiring test (mirrors @Autowired). */
    public void setRuntime(UssdDemoRuntime runtime) {
        this.runtime = runtime;
    }

    public void setGrpcClientForTesting(MockGrpcMenuClient client) {
        this.grpcClient = client;
    }

    @Override public void sbbCreate() { LOG.debug("GrpcBackendSbb created"); }
    @Override public void sbbActivate() { LOG.debug("GrpcBackendSbb activated"); }
    @Override public void sbbPassivate() { }
    @Override public void sbbRemove() { }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof GrpcBackendRequestEvent) {
            onGrpcRequest((GrpcBackendRequestEvent) event, aci);
        }
    }

    private void onGrpcRequest(GrpcBackendRequestEvent event, ActivityContextInterface aci) {
        LOG.infof("[gRPC-backend] ResolveMenu session=%s msisdn=%s",
                event.getSessionId(), event.getMsisdn());
        try {
            String menu = grpcClient.fetchMenu(event.getMsisdn(), event.getUssdString());
            runtime.routeEvent(
                    new GrpcBackendResponseEvent(event.getSessionId(), menu), aci);
        } catch (RuntimeException e) {
            LOG.errorf(e, "[gRPC-backend] failed session=%s", event.getSessionId());
            runtime.failSession(event.getSessionId(), e.getMessage());
        }
    }
}
