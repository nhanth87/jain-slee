/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.sbbs;

import com.example.ussddemo.embedded.EmbeddedUssdMain;
import com.example.ussddemo.events.GrpcBackendRequestEvent;
import com.example.ussddemo.events.GrpcBackendResponseEvent;
import com.example.ussddemo.events.Ss7UssdBeginEvent;
import com.example.ussddemo.events.UssdResponseEvent;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ChildRelation;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.TimerFiredEvent;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SbbLifecycleManager;
import com.microjainslee.core.SimpleSbbLocalObject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Internal MAP/USSD service leg. Registered at runtime via {@code registerSbbType}.
 */
public final class Ss7UssdIngressSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(Ss7UssdIngressSbb.class);
    private static final long SESSION_TIMEOUT_MS = 30_000L;

    private volatile SbbLocalObject self;
    private volatile long sessionTimerId = -1L;

    public void bindSelf(SbbLocalObject self) {
        this.self = self;
    }

    public void initCmp(String sessionId, String msisdn, String menuTier) {
        setSessionId(sessionId);
        setMsisdn(msisdn);
        setMenuTier(menuTier);
    }

    public String getSessionId() {
        return (String) cmpRead(getter("getSessionId"));
    }

    public void setSessionId(String sessionId) {
        cmpWrite(setter("setSessionId", String.class), sessionId);
    }

    public String getMsisdn() {
        return (String) cmpRead(getter("getMsisdn"));
    }

    public void setMsisdn(String msisdn) {
        cmpWrite(setter("setMsisdn", String.class), msisdn);
    }

    public String getMenuTier() {
        return (String) cmpRead(getter("getMenuTier"));
    }

    public void setMenuTier(String menuTier) {
        cmpWrite(setter("setMenuTier", String.class), menuTier);
    }

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
        cancelSessionTimer();
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof Ss7UssdBeginEvent) {
            onSs7Begin((Ss7UssdBeginEvent) event, aci);
        } else if (event instanceof GrpcBackendResponseEvent) {
            onGrpcResponse((GrpcBackendResponseEvent) event, aci);
        } else if (event instanceof TimerFiredEvent) {
            onTimer((TimerFiredEvent) event, aci);
        }
    }

    private void onSs7Begin(Ss7UssdBeginEvent event, ActivityContextInterface aci) {
        LOG.info("[SS7-ingress] MAP begin session={} msisdn={} tier={} text={}",
                getSessionId(), getMsisdn(), getMenuTier(), event.getUssdString());

        MicroSleeContainer container = EmbeddedUssdMain.container();
        sessionTimerId = container.getTimerPort().setTimer(SESSION_TIMEOUT_MS, self);

        SimpleSbbLocalObject parentLo = (SimpleSbbLocalObject) self;
        ChildRelation grpcChildren = parentLo.getChildRelation("grpc",
                container.getChildRelationFactory(GrpcClientSbb.class));
        try {
            SbbLocalObject grpcLo = grpcChildren.create();
            grpcLo.setPriority(5);
            container.attach(getSessionId(), grpcLo);
            waitForActivation((SimpleSbbLocalObject) grpcLo);
            ((GrpcClientSbb) ((SimpleSbbLocalObject) grpcLo).getSbb()).bindSelf(grpcLo);
        } catch (Exception e) {
            LOG.error("Failed to create GrpcClientSbb child for session={}", getSessionId(), e);
            EmbeddedUssdMain.runtime().failSession(getSessionId(), "grpc-child-create-failed");
            return;
        }

        container.routeEvent(new GrpcBackendRequestEvent(
                getSessionId(), getMsisdn(), event.getUssdString()), aci);
    }

    private void onGrpcResponse(GrpcBackendResponseEvent event, ActivityContextInterface aci) {
        cancelSessionTimer();
        String ussdText = "USSD menu for session " + getSessionId()
                + " (tier " + getMenuTier() + "):\n" + event.getMenuText();
        LOG.info("[SS7-ingress] MAP response ready session={}", getSessionId());
        EmbeddedUssdMain.container().routeEvent(
                new UssdResponseEvent(getSessionId(), ussdText), aci);
    }

    private void onTimer(TimerFiredEvent event, ActivityContextInterface aci) {
        if (event.getSbbLocalObject() != self) {
            return;
        }
        LOG.warn("[SS7-ingress] session timeout session={}", getSessionId());
        EmbeddedUssdMain.runtime().failSession(getSessionId(), "session timeout");
        EmbeddedUssdMain.bootstrap().releaseSession(getSessionId());
    }

    private void cancelSessionTimer() {
        if (sessionTimerId >= 0L) {
            EmbeddedUssdMain.container().getTimerPort().cancelTimer(sessionTimerId);
            sessionTimerId = -1L;
        }
    }

    private static Method getter(String name) {
        try {
            return Ss7UssdIngressSbb.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method setter(String name, Class<?> type) {
        try {
            return Ss7UssdIngressSbb.class.getMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void waitForActivation(SimpleSbbLocalObject lo) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (lo.getEntityState().getLifecycleState() == SbbLifecycleManager.State.READY) {
                return;
            }
            Thread.sleep(10L);
        }
    }
}
