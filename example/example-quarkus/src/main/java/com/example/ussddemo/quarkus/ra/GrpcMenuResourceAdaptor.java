/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.ra;

import com.example.ussddemo.quarkus.events.GrpcMenuRequestEvent;
import com.example.ussddemo.quarkus.events.GrpcMenuResponseEvent;
import com.example.ussddemo.quarkus.grpc.GrpcMenuUpstream;
import com.example.ussddemo.quarkus.grpc.proto.MenuResponse;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * gRPC Resource Adaptor — async upstream call to grpc-simulator, events
 * routed on the USSD session activity so pooled SBBs receive them.
 */
public final class GrpcMenuResourceAdaptor implements ResourceAdaptor {

    private static final Logger LOG = Logger.getLogger(GrpcMenuResourceAdaptor.class);

    private ResourceAdaptorContext context;
    private GrpcMenuUpstream client;
    private ExecutorService workerPool;

    public void setGrpcMenuClient(GrpcMenuUpstream client) {
        this.client = client;
    }

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
    }

    @Override
    public void raConfigure() {
        this.workerPool = Executors.newVirtualThreadPerTaskExecutor();
        LOG.info("gRPC-RA configured (virtual-thread worker pool)");
    }

    @Override
    public void raActive() {
        LOG.info("gRPC-RA active");
    }

    @Override
    public void raStopping() {
        LOG.info("gRPC-RA stopping");
    }

    @Override
    public void raInactive() {
        LOG.info("gRPC-RA inactive");
        if (workerPool != null) {
            workerPool.shutdown();
        }
    }

    @Override
    public void raUnconfigure() {
        LOG.info("gRPC-RA unconfigured");
        if (workerPool != null) {
            workerPool.shutdownNow();
            workerPool = null;
        }
    }

    public void requestMenu(String sessionId, String msisdn, String ussdString) {
        if (context == null) {
            LOG.warn("gRPC-RA.requestMenu called before setResourceAdaptorContext");
            return;
        }
        if (client == null) {
            LOG.warn("gRPC-RA.requestMenu called before setGrpcMenuClient");
            return;
        }
        MicroSleeContainer container = container();
        if (container == null) {
            LOG.warn("gRPC-RA has no MicroSleeContainer");
            return;
        }
        ActivityContextInterface sessionAci =
                container.getActivityContextNamingFacility().lookup(sessionId);
        if (sessionAci == null) {
            LOG.warnf("gRPC-RA unknown session activity: %s", sessionId);
            return;
        }
        container.routeEvent(new GrpcMenuRequestEvent(sessionId, msisdn, ussdString), sessionAci);
        workerPool.submit(() -> doCall(sessionId, msisdn, ussdString, sessionAci));
    }

    private void doCall(String sessionId, String msisdn, String ussdString,
                          ActivityContextInterface sessionAci) {
        MicroSleeContainer container = container();
        MenuResponse resp;
        try {
            resp = client.resolveMenu(msisdn, ussdString, sessionId);
        } catch (Throwable t) {
            LOG.warnf(t, "gRPC-RA call failed for session=%s", sessionId);
            if (container != null) {
                container.routeEvent(new GrpcMenuResponseEvent(
                        sessionId, "ERR", null,
                        t.getClass().getSimpleName() + ": " + t.getMessage()), sessionAci);
            }
            return;
        }
        if (container != null) {
            container.routeEvent(new GrpcMenuResponseEvent(
                    resp.getSessionId(), resp.getStatus(), resp.getMenuText(),
                    resp.getError()), sessionAci);
        }
    }

    private MicroSleeContainer container() {
        if (context instanceof RaBootstrapContextImpl) {
            return ((RaBootstrapContextImpl) context).getContainer();
        }
        return null;
    }
}
