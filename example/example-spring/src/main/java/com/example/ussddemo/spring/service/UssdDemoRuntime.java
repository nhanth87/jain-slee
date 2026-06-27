/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.service;

import com.example.ussddemo.spring.events.UssdBeginEvent;
import com.example.ussddemo.spring.grpc.MockGrpcMenuClient;
import com.example.ussddemo.spring.sbbs.GrpcBackendSbb;
import com.example.ussddemo.spring.sbbs.Ss7UssdIngressSbb;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.InMemoryActivityContext;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;
import org.jboss.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Spring bridge. The {@code jainslee-spring-boot-starter} auto-config
 * produces {@link MicroSleeContainer} as a Spring bean; we inject it
 * here and register per-session SBBs.
 *
 * <p>The SBBs are constructed manually (not by the APT scanner) so we
 * can pass {@code this} as the ctor arg so they hold a reference back
 * to the runtime ({@code routeEvent} / {@code completeSession}). The
 * no-arg ctor is used by the APT auto-deploy path, with the runtime
 * wired in via a setter from the wiring test.
 */
@Service
public class UssdDemoRuntime {

    private static final Logger LOG = Logger.getLogger(UssdDemoRuntime.class);

    public static final String SS7_SBB_ID = "Ss7UssdIngress";
    public static final String GRPC_SBB_ID = "GrpcBackend";

    /**
     * Per-session SBB IDs -- suffixed with the session id so concurrent
     * sessions don't collide on the (internally-cached) auto-deployed
     * SBB entries from the sbb-index.properties.
     */
    private String ss7Id(String sessionId) {
        return SS7_SBB_ID + "/" + sessionId;
    }

    private String grpcId(String sessionId) {
        return GRPC_SBB_ID + "/" + sessionId;
    }

    @Autowired
    private MicroSleeContainer container;

    @Autowired
    private UssdSessionStore sessionStore;

    @Autowired
    private UssdCallbackDispatcher callbackDispatcher;

    @Autowired
    private MockGrpcMenuClient grpcClient;

    public MicroSleeContainer container() { return container; }
    public UssdSessionStore sessionStore() { return sessionStore; }

    public void beginSession(String sessionId, String msisdn, String ussdString, String callbackUrl) {
        sessionStore.open(sessionId);
        sessionStore.attachCallback(sessionId, callbackUrl);

        InMemoryActivityContext aci = container.createActivityContext(sessionId);
        Ss7UssdIngressSbb ss7Sbb = new Ss7UssdIngressSbb(this);
        GrpcBackendSbb grpcSbb = new GrpcBackendSbb(this);
        if (grpcClient != null) {
            grpcSbb.setGrpcClientForTesting(grpcClient);
        }
        SimpleSbbLocalObject ss7 = container.registerSbb(ss7Id(sessionId), ss7Sbb);
        SimpleSbbLocalObject grpc = container.registerSbb(grpcId(sessionId), grpcSbb);
        ss7.setPriority(8);
        grpc.setPriority(5);
        container.attach(sessionId, ss7);
        container.attach(sessionId, grpc);

        LOG.infof("Firing UssdBeginEvent session=%s msisdn=%s callback=%s",
                sessionId, msisdn,
                callbackUrl == null ? "<polling>" : callbackUrl);
        container.routeEvent(new UssdBeginEvent(sessionId, msisdn, ussdString), aci);
    }

    public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
        container.routeEvent(event, aci);
    }

    public void completeSession(String sessionId, String responseText) {
        sessionStore.complete(sessionId, responseText);
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record != null && record.getCallbackUrl() != null) {
            callbackDispatcher.dispatch(record.getCallbackUrl(), sessionId, "COMPLETED",
                    responseText, null);
        }
    }

    public void failSession(String sessionId, String message) {
        sessionStore.fail(sessionId, message);
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record != null && record.getCallbackUrl() != null) {
            callbackDispatcher.dispatch(record.getCallbackUrl(), sessionId, "FAILED",
                    null, message);
        }
    }
}
