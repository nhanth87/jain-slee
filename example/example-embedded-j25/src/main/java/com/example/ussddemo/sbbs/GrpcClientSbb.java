/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.sbbs;

import com.example.ussddemo.embedded.EmbeddedUssdMain;
import com.example.ussddemo.events.GrpcBackendRequestEvent;
import com.example.ussddemo.events.GrpcBackendResponseEvent;
import com.example.ussddemo.events.GrpcMenuResponseEvent;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Child SBB that bridges the USSD session to the gRPC menu RA.
 * Registered at runtime via {@code registerSbbType}.
 */
public final class GrpcClientSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(GrpcClientSbb.class);

    private volatile SbbLocalObject self;

    public void bindSelf(SbbLocalObject self) {
        this.self = self;
    }

    @Override
    public void sbbCreate() {
        LOG.debug("GrpcClientSbb created");
    }

    @Override
    public void sbbActivate() {
        LOG.debug("GrpcClientSbb activated");
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
        } else if (event instanceof GrpcMenuResponseEvent) {
            onGrpcMenuResponse((GrpcMenuResponseEvent) event, aci);
        }
    }

    private void onGrpcRequest(GrpcBackendRequestEvent event, ActivityContextInterface aci) {
        LOG.info("[gRPC-client] ResolveMenu session={} msisdn={}",
                event.getSessionId(), event.getMsisdn());
        EmbeddedUssdMain.grpcRa().requestMenu(
                event.getSessionId(), event.getMsisdn(), event.getUssdString(), aci);
    }

    private void onGrpcMenuResponse(GrpcMenuResponseEvent event, ActivityContextInterface aci) {
        String menu = "OK".equals(event.getStatus())
                ? event.getMenuText()
                : "ERR: " + event.getError();
        LOG.info("[gRPC-client] menu response session={} status={}",
                event.getSessionId(), event.getStatus());
        EmbeddedUssdMain.container().routeEvent(
                new GrpcBackendResponseEvent(event.getSessionId(), menu), aci);
    }
}
