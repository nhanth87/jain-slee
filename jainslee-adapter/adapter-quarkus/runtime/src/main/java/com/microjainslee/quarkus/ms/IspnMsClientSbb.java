/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus.ms;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.exception.ServiceCallTimeoutException;
import com.microjainslee.ms.api.exception.ServiceUnavailableException;
import com.microjainslee.ms.ispn.ra.IspnQueueCommand;
import com.microjainslee.ms.ispn.ra.IspnQueueRaEndpoint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Child / collaborator SBB that owns the only SBB-visible path to
 * {@code ispn-queue-ra} (ADR 0002). Parents call {@link #call}, {@link #notify},
 * {@link #queryState} instead of {@code MicrosleeBootstrap.client()}.
 *
 * <p>Port resolution: {@code @InjectRa} when this SBB is pooled as an entity,
 * or an explicit {@link Supplier} when the parent wires a child collaborator.
 */
public final class IspnMsClientSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(IspnMsClientSbb.class);

    /** Default wait for command futures (ISPN client has its own call timeout). */
    private static final long COMMAND_WAIT_MS = 60_000L;

    @InjectRa(name = IspnQueueRaEndpoint.RA_NAME)
    private volatile RaCommandPort ispnRa;

    private final Supplier<RaCommandPort> portFallback;
    private volatile SbbLocalObject self;

    public IspnMsClientSbb() {
        this.portFallback = null;
    }

    /**
     * Child collaborator: resolve {@code ispn-queue-ra} from the container
     * (parent does not call ISPN APIs itself).
     */
    public IspnMsClientSbb(Supplier<RaCommandPort> portFallback) {
        this.portFallback = Objects.requireNonNull(portFallback, "portFallback");
    }

    public void bindSelf(SbbLocalObject self) {
        this.self = self;
    }

    /** Test / wire hook when {@code @InjectRa} has not run yet. */
    public void bindRa(RaCommandPort port) {
        this.ispnRa = port;
    }

    @Override
    public void sbbCreate() {
    }

    @Override
    public void sbbActivate() {
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        // Sync API is via call/notify/queryState; no inbound events in phase 1.
    }

    public SleeResponse call(String serviceName, SleeRequest request) {
        Objects.requireNonNull(serviceName, "serviceName");
        RaCommandPort port = requirePort();
        CompletableFuture<SleeResponse> reply = new CompletableFuture<>();
        port.sendCommand(new IspnQueueCommand.CallService(serviceName, request, reply));
        return await(reply, serviceName);
    }

    public void notify(String serviceName, SleeRequest request) {
        Objects.requireNonNull(serviceName, "serviceName");
        RaCommandPort port = requirePort();
        CompletableFuture<Void> done = new CompletableFuture<>();
        port.sendCommand(new IspnQueueCommand.NotifyService(serviceName, request, done));
        await(done, serviceName);
    }

    public ServiceState queryState(String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName");
        RaCommandPort port = requirePort();
        CompletableFuture<ServiceState> reply = new CompletableFuture<>();
        port.sendCommand(new IspnQueueCommand.QueryServiceState(serviceName, reply));
        return await(reply, serviceName);
    }

    private RaCommandPort requirePort() {
        RaCommandPort port = this.ispnRa;
        if (port == null && portFallback != null) {
            port = portFallback.get();
        }
        if (port == null) {
            throw new IllegalStateException(
                    IspnQueueRaEndpoint.RA_NAME + " command port not available");
        }
        return port;
    }

    private static <T> T await(CompletableFuture<T> future, String serviceName) {
        try {
            return future.get(COMMAND_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new ServiceCallTimeoutException(
                    "Service '" + serviceName + "' call timed out after " + COMMAND_WAIT_MS + "ms");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for MS call to " + serviceName, ex);
        } catch (ExecutionException ex) {
            throw unwrap(ex.getCause());
        } catch (CompletionException ex) {
            throw unwrap(ex.getCause());
        }
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof ServiceUnavailableException sue) {
            return sue;
        }
        if (cause instanceof ServiceCallTimeoutException ste) {
            return ste;
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new IllegalStateException(cause == null ? "MS call failed" : cause.getMessage(), cause);
    }
}
