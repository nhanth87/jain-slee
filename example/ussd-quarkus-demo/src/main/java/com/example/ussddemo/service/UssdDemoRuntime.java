/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.service;

import com.example.ussddemo.runtime.EmbeddedMicroJainsleeProducer;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.InMemoryActivityContext;
import com.microjainslee.core.SimpleSbbLocalObject;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Bridges Quarkus CDI beans and the embedded {@link MicroSleeContainer}.
 */
@ApplicationScoped
@Unremovable
public final class UssdDemoRuntime {

    private static final Logger LOG = Logger.getLogger(UssdDemoRuntime.class);

    public static final String SS7_SBB_ID = "Ss7UssdIngress";
    public static final String GRPC_SBB_ID = "GrpcBackend";

    @Inject
    EmbeddedMicroJainsleeProducer microJainslee;

    @Inject
    UssdSessionStore sessionStore;

    public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
        microJainslee.container().routeEvent(event, aci);
    }

    public void completeSession(String sessionId, String responseText) {
        sessionStore.complete(sessionId, responseText);
    }

    public void failSession(String sessionId, String message) {
        sessionStore.fail(sessionId, message);
    }

    public InMemoryActivityContext createSessionActivityContext(String sessionId) {
        return microJainslee.container().createActivityContext(sessionId);
    }

    public void attachDemoSbbs(String sessionId) {
        SimpleSbbLocalObject ss7 = microJainslee.container().registerSbb(
                SS7_SBB_ID, new com.example.ussddemo.sbbs.Ss7UssdIngressSbb());
        SimpleSbbLocalObject grpc = microJainslee.container().registerSbb(
                GRPC_SBB_ID, new com.example.ussddemo.sbbs.GrpcBackendSbb());
        ss7.setPriority(8);
        grpc.setPriority(5);
        microJainslee.container().attach(sessionId, ss7);
        microJainslee.container().attach(sessionId, grpc);
        LOG.infof("Attached SBBs to session %s (Ss7UssdIngress + GrpcBackend)", sessionId);
    }
}
