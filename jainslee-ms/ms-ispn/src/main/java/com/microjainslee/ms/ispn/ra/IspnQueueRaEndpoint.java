/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn.ra;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnTransportManager;

import java.util.Objects;

/**
 * 3-port adapter for {@link IspnQueueResourceAdaptor} ({@code ispn-queue-ra}).
 */
public final class IspnQueueRaEndpoint implements RaEndpointPort, RaCommandPort {

    public static final String RA_NAME = "ispn-queue-ra";

    private final IspnQueueResourceAdaptor delegate;

    public IspnQueueRaEndpoint(
            MicrosleeBootstrap bootstrap,
            IspnTransportManager transport,
            DeploymentConfig config) {
        this(bootstrap, transport, config, InboundMode.HANDLER);
    }

    public IspnQueueRaEndpoint(
            MicrosleeBootstrap bootstrap,
            IspnTransportManager transport,
            DeploymentConfig config,
            InboundMode inboundMode) {
        this.delegate = new IspnQueueResourceAdaptor(bootstrap, transport, config, inboundMode);
    }

    public IspnQueueRaEndpoint(IspnQueueResourceAdaptor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public IspnQueueResourceAdaptor adaptor() {
        return delegate;
    }

    @Override
    public String getRaName() {
        return RA_NAME;
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        delegate.activate(bootstrap);
    }

    @Override
    public void deactivate() {
        delegate.deactivate();
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        delegate.sendCommand(command);
    }
}
