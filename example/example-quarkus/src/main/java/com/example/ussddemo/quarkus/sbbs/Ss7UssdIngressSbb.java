/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.sbbs;

import com.example.ussddemo.quarkus.events.GrpcMenuResponseEvent;
import com.example.ussddemo.quarkus.events.Ss7UssdBeginEvent;
import com.example.ussddemo.quarkus.events.UssdCompleteEvent;
import com.example.ussddemo.quarkus.service.UssdSbbWiring;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;

/**
 * Internal MAP/USSD service leg — CMP fields + gRPC RA delegate.
 */
@SbbAnnotation(name = "Ss7UssdIngress", vendor = "com.example.ussddemo.quarkus", version = "1.0")
public final class Ss7UssdIngressSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = Logger.getLogger(Ss7UssdIngressSbb.class);

    private final UssdSbbWiring wiring;

    private String sessionId;
    private String msisdn;
    private int menuTier = 1;

    public Ss7UssdIngressSbb(UssdSbbWiring wiring) {
        this.wiring = wiring;
    }

    public Ss7UssdIngressSbb() {
        this.wiring = null;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMsisdn() { return msisdn; }
    public void setMsisdn(String msisdn) { this.msisdn = msisdn; }
    public int getMenuTier() { return menuTier; }
    public void setMenuTier(int menuTier) { this.menuTier = menuTier; }

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
        cmpWrite(method("setSessionId", String.class), event.getSessionId());
        cmpWrite(method("setMsisdn", String.class), event.getMsisdn());
        cmpWrite(method("setMenuTier", int.class), event.getMenuTier());
        LOG.infof("[SS7-ingress] MAP begin session=%s msisdn=%s tier=%d cmpTier=%d",
                event.getSessionId(), event.getMsisdn(), event.getMenuTier(), getMenuTier());
        // Pass `aci` as the 4th arg so the gRPC response is routed back to
        // the SS7-ingress SBB instead of staying on the gRPC-client's
        // request ACI. Matches the canonical `ras/ra-grpc-client` 4-arg
        // signature introduced in audit fix 2026-06-28.
        wiring.grpcRa().requestMenu(event.getSessionId(), event.getMsisdn(), event.getUssdString(), aci);
    }

    private void onGrpcResponse(GrpcMenuResponseEvent event, ActivityContextInterface aci) {
        String menu = "OK".equals(event.getStatus())
                ? event.getMenuText()
                : "ERR: " + event.getError();
        int tier = 1;
        Object tierObj = cmpRead(method("getMenuTier"));
        if (tierObj instanceof Integer) {
            tier = ((Integer) tierObj).intValue();
        }
        String ussdText = "USSD menu for session " + event.getSessionId()
                + " (tier " + tier + "):\n" + menu;
        LOG.infof("[SS7-ingress] MAP response ready session=%s", event.getSessionId());
        wiring.routeEvent(new UssdCompleteEvent(event.getSessionId(), ussdText), aci);
    }

    private Method method(String name, Class<?>... params) {
        try {
            return Ss7UssdIngressSbb.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
