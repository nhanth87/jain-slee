/*
 * micro-jainslee example — Jakarta EE Hello World
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.example.helloworld.jakartaee.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Minimal SBB — logs Hello World for each ra-http-server request. */
public final class HelloWorldSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(HelloWorldSbb.class);

    private final MicroSleeContainer container;

    /** Injected by container at activation; must match RaEndpointPort.getRaName(). */
    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort httpCommandPort;

    public HelloWorldSbb(MicroSleeContainer container) {
        this.container = java.util.Objects.requireNonNull(container, "container");
    }

    public void bindSelf(SbbLocalObject self) {
        // reserved for session attach patterns (spring/quarkus helloworld)
    }

    @Override
    public void sbbCreate() {
        LOG.debug("HelloWorldSbb created");
    }

    @Override
    public void sbbActivate() {
        LOG.debug("HelloWorldSbb activated");
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof HttpWebRequestEvent req) {
            LOG.info("[HelloWorld/JakartaEE] web request session={} {} {}",
                    req.getSessionId(), req.getMethod(), req.getPath());
            String userAgent = req.getUserAgent() != null ? req.getUserAgent() : "unknown";
            LOG.info("[HelloWorld/JakartaEE] Hello World {}", userAgent);
        }
    }
}
