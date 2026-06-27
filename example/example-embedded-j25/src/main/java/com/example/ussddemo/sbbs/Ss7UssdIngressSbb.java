/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.sbbs;

import com.example.ussddemo.embedded.EmbeddedUssdMain;
import com.example.ussddemo.events.GrpcBackendRequestEvent;
import com.example.ussddemo.events.GrpcBackendResponseEvent;
import com.example.ussddemo.events.UssdBeginEvent;
import com.example.ussddemo.events.UssdResponseEvent;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.SbbAnnotation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simulates the SS7 ingress leg. Reaches the bridge via the static
 * {@link EmbeddedUssdMain#runtime()} handle (no CDI available in the
 * embedded variant).
 */
@SbbAnnotation(name = "Ss7UssdIngress", vendor = "com.example.ussddemo", version = "1.0")
public final class Ss7UssdIngressSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(Ss7UssdIngressSbb.class);

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
        LOG.info("[SS7-ingress] MAP USSD begin session={} msisdn={} text={}", event.getSessionId(), event.getMsisdn(), event.getUssdString());
        EmbeddedUssdMain.runtime().routeEvent(
                new GrpcBackendRequestEvent(event.getSessionId(), event.getMsisdn(),
                        event.getUssdString()),
                aci);
    }

    private void onGrpcResponse(GrpcBackendResponseEvent event, ActivityContextInterface aci) {
        String ussdText = "USSD menu for session " + event.getSessionId() + ":\n"
                + event.getMenuText();
        LOG.info("[SS7-ingress] MAP USSD response ready session={}", event.getSessionId());
        EmbeddedUssdMain.runtime().routeEvent(
                new UssdResponseEvent(event.getSessionId(), ussdText), aci);
        EmbeddedUssdMain.runtime().completeSession(event.getSessionId(), ussdText);
    }
}
