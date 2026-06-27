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
import com.example.ussddemo.spring.events.UssdBeginEvent;
import com.example.ussddemo.spring.events.UssdResponseEvent;
import com.example.ussddemo.spring.service.UssdDemoRuntime;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.SbbAnnotation;
import org.jboss.logging.Logger;

/**
 * Simulates the SS7 ingress leg. Constructor-injected runtime (Spring
 * autowires via the no-arg ctor when used by ARC/CDI proxies, and via
 * the single-arg ctor when the runtime itself instantiates the SBB).
 */
@SbbAnnotation(name = "Ss7UssdIngress", vendor = "com.example.ussddemo", version = "1.0")
public final class Ss7UssdIngressSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = Logger.getLogger(Ss7UssdIngressSbb.class);

    private UssdDemoRuntime runtime;

    public Ss7UssdIngressSbb() {
    }

    public Ss7UssdIngressSbb(UssdDemoRuntime runtime) {
        this.runtime = runtime;
    }

    /** Spring setter (mirrors @Autowired) for the wiring test. */
    public void setRuntime(UssdDemoRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void sbbCreate() { LOG.debug("Ss7UssdIngressSbb created"); }
    @Override
    public void sbbActivate() { LOG.debug("Ss7UssdIngressSbb activated"); }
    @Override public void sbbPassivate() { }
    @Override public void sbbRemove() { }

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
        runtime.routeEvent(
                new GrpcBackendRequestEvent(event.getSessionId(), event.getMsisdn(),
                        event.getUssdString()),
                aci);
    }

    private void onGrpcResponse(GrpcBackendResponseEvent event, ActivityContextInterface aci) {
        String ussdText = "USSD menu for session " + event.getSessionId() + ":\n"
                + event.getMenuText();
        LOG.infof("[SS7-ingress] MAP USSD response ready session=%s", event.getSessionId());
        runtime.routeEvent(new UssdResponseEvent(event.getSessionId(), ussdText), aci);
        runtime.completeSession(event.getSessionId(), ussdText);
    }
}
