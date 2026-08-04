/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 3-port endpoint for ra-jss7.
 * RA name: {@code "ra-jss7"} (used by SBB {@code @InjectRa} lookup).
 */
public final class Ss7RaEndpoint implements RaEndpointPort, RaCommandPort {

    private static final Logger LOG = LogManager.getLogger(Ss7RaEndpoint.class);

    private final Ss7ResourceAdaptor delegate;
    private final String raName;

    public Ss7RaEndpoint(Ss7ResourceAdaptor delegate, String raName) {
        this.delegate = delegate;
        this.raName = raName;
        this.delegate.setRaName(raName);
    }

    public Ss7RaEndpoint(Ss7ResourceAdaptor delegate) {
        this(delegate, "ra-jss7");
    }

    public Ss7RaEndpoint() {
        this(new Ss7ResourceAdaptor());
    }

    public Ss7ResourceAdaptor delegate() { return delegate; }

    /** RA lifecycle active — not peer route-ready; use {@link #isM3uaRouteReady()}. */
    public boolean isActive() {
        return delegate.isActive();
    }

    /** Honest SS7 link UP: SCTP assoc up + M3UA AS ACTIVE. */
    public boolean isM3uaRouteReady() {
        return delegate.isM3uaRouteReady();
    }

    @Override public String getRaName() { return raName; }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        delegate.setBootstrapPort(bootstrap);
        delegate.raActive();
        LOG.info("jSS7 RA [{}] activated", raName);
    }

    @Override
    public void deactivate() {
        delegate.raInactive();
        LOG.info("jSS7 RA [{}] deactivated", raName);
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof Ss7Command sc) {
            delegate.sendOutbound(sc);
        } else {
            LOG.warn("jSS7 RA [{}] unknown command: {}", raName,
                     command == null ? "null" : command.getClass().getName());
        }
    }
}
