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

import com.example.ussddemo.spring.events.GrpcMenuResponseEvent;
import com.example.ussddemo.spring.events.Ss7UssdBeginEvent;
import com.example.ussddemo.spring.events.UssdCompleteEvent;
import com.example.ussddemo.spring.service.UssdWiring;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;

import org.jboss.logging.Logger;

import java.lang.reflect.Method;

/**
 * Internal SS7/MAP USSD leg. Persists session state in CMP fields and
 * delegates menu resolution to the gRPC RA.
 */
@SbbAnnotation(name = "Ss7UssdIngress", vendor = "com.example.ussddemo", version = "1.0")
public final class Ss7UssdIngressSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = Logger.getLogger(Ss7UssdIngressSbb.class);

    private UssdWiring wiring;

    public Ss7UssdIngressSbb() {
    }

    public Ss7UssdIngressSbb(UssdWiring wiring) {
        this.wiring = wiring;
    }

    public void setWiring(UssdWiring wiring) {
        this.wiring = wiring;
    }

    @CmpField("sessionId")
    public String getSessionId() {
        return (String) cmpRead(accessor("getSessionId"));
    }

    public void setSessionId(String sessionId) {
        cmpWrite(accessor("setSessionId", String.class), sessionId);
    }

    @CmpField("msisdn")
    public String getMsisdn() {
        return (String) cmpRead(accessor("getMsisdn"));
    }

    public void setMsisdn(String msisdn) {
        cmpWrite(accessor("setMsisdn", String.class), msisdn);
    }

    @CmpField("menuTier")
    public int getMenuTier() {
        Integer v = (Integer) cmpRead(accessor("getMenuTier"));
        return v == null ? 0 : v;
    }

    public void setMenuTier(int menuTier) {
        cmpWrite(accessor("setMenuTier", int.class), menuTier);
    }

    @Override public void sbbCreate() { LOG.debug("Ss7UssdIngressSbb created"); }
    @Override public void sbbActivate() { LOG.debug("Ss7UssdIngressSbb activated"); }
    @Override public void sbbPassivate() { }
    @Override public void sbbRemove() { }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof Ss7UssdBeginEvent) {
            onSs7Begin((Ss7UssdBeginEvent) event, aci);
        } else if (event instanceof GrpcMenuResponseEvent) {
            onGrpcResponse((GrpcMenuResponseEvent) event, aci);
        }
    }

    private void onSs7Begin(Ss7UssdBeginEvent event, ActivityContextInterface aci) {
        setSessionId(event.getSessionId());
        setMsisdn(event.getMsisdn());
        setMenuTier(event.getMenuTier());
        LOG.infof("[SS7-ingress] MAP begin session=%s msisdn=%s tier=%d",
                event.getSessionId(), event.getMsisdn(), event.getMenuTier());
        wiring.grpcRa().requestMenu(event.getSessionId(), event.getMsisdn(),
                event.getUssdString(), aci);
    }

    private void onGrpcResponse(GrpcMenuResponseEvent event, ActivityContextInterface aci) {
        if (!"OK".equalsIgnoreCase(event.getStatus())) {
            wiring.container().routeEvent(new UssdCompleteEvent(
                    event.getSessionId(), null, event.getError()), aci);
            return;
        }
        String ussdText = "USSD menu for session " + event.getSessionId() + ":\n"
                + event.getMenuText();
        LOG.infof("[SS7-ingress] MAP response ready session=%s", event.getSessionId());
        wiring.container().routeEvent(new UssdCompleteEvent(
                event.getSessionId(), ussdText, null), aci);
    }

    private static Method accessor(String name, Class<?>... params) {
        try {
            return Ss7UssdIngressSbb.class.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
