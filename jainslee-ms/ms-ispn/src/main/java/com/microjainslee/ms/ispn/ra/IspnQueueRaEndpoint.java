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
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceClient;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnTransportManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * MS transport RA: SBB outbound goes through {@link RaCommandPort}; ISPN /
 * Direct {@link SleeServiceClient} stays inside this RA (ADR 0002).
 */
public final class IspnQueueRaEndpoint implements RaEndpointPort, RaCommandPort {

    public static final String RA_NAME = "ispn-queue-ra";

    private static final Logger LOG = LogManager.getLogger(IspnQueueRaEndpoint.class);

    private final MicrosleeBootstrap bootstrap;
    private final IspnTransportManager transport;
    private volatile boolean active;

    public IspnQueueRaEndpoint(
            MicrosleeBootstrap bootstrap,
            IspnTransportManager transport,
            DeploymentConfig config) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.transport = Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(config, "config"); // retained for call-site compatibility with MicrosleeMsSupport
    }

    @Override
    public String getRaName() {
        return RA_NAME;
    }

    @Override
    public void activate(RaBootstrapPort bootstrapPort) {
        this.active = true;
        LOG.info("{} activated (MS Direct/Infinispan via SleeServiceClient)", RA_NAME);
    }

    @Override
    public void deactivate() {
        this.active = false;
        LOG.info("{} deactivated", RA_NAME);
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (!active) {
            failFuture(command, new IllegalStateException(RA_NAME + " is not active"));
            return;
        }
        if (!(command instanceof IspnQueueCommand cmd)) {
            throw new IllegalArgumentException(
                    "Unsupported command type: " + command.getClass().getName());
        }
        switch (cmd) {
            case IspnQueueCommand.CallService call -> handleCall(call);
            case IspnQueueCommand.NotifyService notify -> handleNotify(notify);
            case IspnQueueCommand.QueryServiceState query -> handleQuery(query);
        }
    }

    private void handleCall(IspnQueueCommand.CallService call) {
        CompletableFuture<SleeResponse> reply = call.reply();
        if (reply == null) {
            throw new IllegalArgumentException("CallService.reply is required");
        }
        try {
            SleeServiceClient<?> client = bootstrap.client(call.serviceName());
            SleeRequest request = call.request() == null
                    ? new SleeRequest("ping", new byte[0])
                    : call.request();
            reply.complete(client.call(request));
        } catch (RuntimeException ex) {
            reply.completeExceptionally(ex);
        }
    }

    private void handleNotify(IspnQueueCommand.NotifyService notify) {
        CompletableFuture<Void> done = notify.done();
        try {
            SleeServiceClient<?> client = bootstrap.client(notify.serviceName());
            SleeRequest request = notify.request() == null
                    ? new SleeRequest("event", new byte[0])
                    : notify.request();
            client.notify(request);
            if (done != null) {
                done.complete(null);
            }
        } catch (RuntimeException ex) {
            if (done != null) {
                done.completeExceptionally(ex);
            } else {
                throw ex;
            }
        }
    }

    private void handleQuery(IspnQueueCommand.QueryServiceState query) {
        CompletableFuture<ServiceState> reply = query.reply();
        if (reply == null) {
            throw new IllegalArgumentException("QueryServiceState.reply is required");
        }
        try {
            if (bootstrap.config().isLocal(query.serviceName())) {
                reply.complete(ServiceState.READY);
            } else {
                reply.complete(transport.stateOf(query.serviceName()));
            }
        } catch (RuntimeException ex) {
            reply.completeExceptionally(ex);
        }
    }

    private static void failFuture(OutboundCommand command, RuntimeException ex) {
        if (command instanceof IspnQueueCommand.CallService call && call.reply() != null) {
            call.reply().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.NotifyService notify && notify.done() != null) {
            notify.done().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.QueryServiceState query && query.reply() != null) {
            query.reply().completeExceptionally(ex);
        } else {
            throw ex;
        }
    }
}
