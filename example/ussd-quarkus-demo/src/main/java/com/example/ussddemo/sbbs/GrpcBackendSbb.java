/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.sbbs;

import com.example.ussddemo.events.GrpcBackendRequestEvent;
import com.example.ussddemo.events.GrpcBackendResponseEvent;
import com.example.ussddemo.grpc.MockGrpcMenuClient;
import com.example.ussddemo.service.UssdDemoRuntime;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.SbbAnnotation;
import io.quarkus.arc.Arc;
import org.jboss.logging.Logger;

/**
 * Simulates a gRPC menu/billing backend SBB. Invoked by the SS7 ingress SBB via
 * {@link GrpcBackendRequestEvent} and responds with {@link GrpcBackendResponseEvent}.
 */
@SbbAnnotation(name = "GrpcBackend", vendor = "com.example.ussddemo", version = "1.0")
public final class GrpcBackendSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = Logger.getLogger(GrpcBackendSbb.class);

    @Override
    public void sbbCreate() {
        LOG.debug("GrpcBackendSbb created");
    }

    @Override
    public void sbbActivate() {
        LOG.debug("GrpcBackendSbb activated");
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof GrpcBackendRequestEvent) {
            onGrpcRequest((GrpcBackendRequestEvent) event, aci);
        }
    }

    private void onGrpcRequest(GrpcBackendRequestEvent event, ActivityContextInterface aci) {
        LOG.infof("[gRPC-backend] ResolveMenu session=%s msisdn=%s", event.getSessionId(), event.getMsisdn());
        try {
            String menu = grpcClient().fetchMenu(event.getMsisdn(), event.getUssdString());
            runtime().routeEvent(new GrpcBackendResponseEvent(event.getSessionId(), menu), aci);
        } catch (RuntimeException e) {
            LOG.errorf(e, "[gRPC-backend] failed session=%s", event.getSessionId());
            runtime().failSession(event.getSessionId(), e.getMessage());
        }
    }

    private static UssdDemoRuntime runtime() {
        return Arc.container().instance(UssdDemoRuntime.class).get();
    }

    private static MockGrpcMenuClient grpcClient() {
        return Arc.container().instance(MockGrpcMenuClient.class).get();
    }
}
