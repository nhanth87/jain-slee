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
import com.example.ussddemo.events.UssdBeginEvent;
import com.example.ussddemo.events.UssdResponseEvent;
import com.example.ussddemo.service.UssdDemoRuntime;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.SbbAnnotation;
import io.quarkus.arc.Arc;
import org.jboss.logging.Logger;

/**
 * Simulates the USSD gateway SS7 ingress leg: receives MAP USSD begin and forwards
 * processing to the gRPC backend SBB through the JAIN SLEE event router.
 */
@SbbAnnotation(name = "Ss7UssdIngress", vendor = "com.example.ussddemo", version = "1.0")
public final class Ss7UssdIngressSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = Logger.getLogger(Ss7UssdIngressSbb.class);

    @Override
    public void sbbCreate() {
        LOG.debug("Ss7UssdIngressSbb created");
    }

    @Override
    public void sbbActivate() {
        LOG.debug("Ss7UssdIngressSbb activated");
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof UssdBeginEvent) {
            onUssdBegin((UssdBeginEvent) event, aci);
        } else if (event instanceof GrpcBackendResponseEvent) {
            onGrpcResponse((GrpcBackendResponseEvent) event, aci);
        }
    }

    private void onUssdBegin(UssdBeginEvent event, ActivityContextInterface aci) {
        LOG.infof("[SS7-ingress] MAP USSD begin session=%s msisdn=%s text=%s",
                event.getSessionId(), event.getMsisdn(), event.getUssdString());
        runtime().routeEvent(
                new GrpcBackendRequestEvent(event.getSessionId(), event.getMsisdn(), event.getUssdString()),
                aci);
    }

    private void onGrpcResponse(GrpcBackendResponseEvent event, ActivityContextInterface aci) {
        String ussdText = "USSD menu for session " + event.getSessionId() + ":\n" + event.getMenuText();
        LOG.infof("[SS7-ingress] MAP USSD response ready session=%s", event.getSessionId());
        runtime().routeEvent(new UssdResponseEvent(event.getSessionId(), ussdText), aci);
        runtime().completeSession(event.getSessionId(), ussdText);
    }

    private static UssdDemoRuntime runtime() {
        return Arc.container().instance(UssdDemoRuntime.class).get();
    }
}
