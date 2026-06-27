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
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.SbbAnnotation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simulates a gRPC menu/billing backend SBB. Reaches the runtime and
 * the mock client via static {@link EmbeddedUssdMain} handles (no CDI).
 */
@SbbAnnotation(name = "GrpcBackend", vendor = "com.example.ussddemo", version = "1.0")
public final class GrpcBackendSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(GrpcBackendSbb.class);

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
        LOG.info("[gRPC-backend] ResolveMenu session={} msisdn={}", event.getSessionId(), event.getMsisdn());
        try {
            String menu = EmbeddedUssdMain.grpcClient().fetchMenu(
                    event.getMsisdn(), event.getUssdString());
            EmbeddedUssdMain.runtime().routeEvent(
                    new GrpcBackendResponseEvent(event.getSessionId(), menu), aci);
        } catch (RuntimeException e) {
            LOG.error("[gRPC-backend] failed session={}", event.getSessionId(), e);
            EmbeddedUssdMain.runtime().failSession(event.getSessionId(), e.getMessage());
        }
    }
}
