/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.grpc;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.spi.AbstractResourceAdaptor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Async gRPC menu Resource Adaptor — fires request/response {@link SleeEvent}s
 * on the USSD session activity via {@link com.microjainslee.api.SleeEndpointPort}.
 */
public final class GrpcMenuResourceAdaptor extends AbstractResourceAdaptor {

    private static final Logger LOG = Logger.getLogger(GrpcMenuResourceAdaptor.class.getName());

    private GrpcMenuUpstream upstream;
    private GrpcMenuEventFactory eventFactory;
    private GrpcActivityContextLookup activityContextLookup;
    private ExecutorService workerPool;

    public void setGrpcMenuUpstream(GrpcMenuUpstream upstream) {
        this.upstream = upstream;
    }

    public void setEventFactory(GrpcMenuEventFactory eventFactory) {
        this.eventFactory = eventFactory;
    }

    public void setActivityContextLookup(GrpcActivityContextLookup activityContextLookup) {
        this.activityContextLookup = activityContextLookup;
    }

    @Override
    public void raConfigure() {
        workerPool = Executors.newVirtualThreadPerTaskExecutor();
        LOG.info("gRPC menu RA configured (virtual-thread worker pool)");
    }

    @Override
    public void raActive() {
        LOG.info("gRPC menu RA active");
    }

    @Override
    public void raStopping() {
        LOG.info("gRPC menu RA stopping");
    }

    @Override
    public void raInactive() {
        if (workerPool != null) {
            workerPool.shutdown();
        }
    }

    @Override
    public void raUnconfigure() {
        if (workerPool != null) {
            workerPool.shutdownNow();
            workerPool = null;
        }
        super.raUnconfigure();
    }

    /**
     * Starts an async upstream menu lookup for the given USSD session.
     *
     * <p>Request event fires on the session ACI (looked up via the
     * configured {@link GrpcActivityContextLookup}); response event fires
     * on the separate {@code responseAci} supplied by the caller. This
     * matches the spec usage where request and response may live on
     * different activity contexts (e.g. SS7 dialog vs gRPC correlation).
     * The {@link com.microjainslee.core.MicroSleeContainer} is required for
     * the response leg because {@link SleeEndpointPort} only fires via
     * activity handle, not via an existing ACI reference.</p>
     */
    public void requestMenu(String sessionId, String msisdn, String ussdString,
                            ActivityContextInterface responseAci) {
        if (upstream == null) {
            LOG.warning("gRPC menu RA requestMenu called before setGrpcMenuUpstream");
            return;
        }
        if (eventFactory == null) {
            LOG.warning("gRPC menu RA requestMenu called before setEventFactory");
            return;
        }
        if (activityContextLookup == null) {
            LOG.warning("gRPC menu RA requestMenu called before setActivityContextLookup");
            return;
        }
        ActivityContextInterface sessionAci = activityContextLookup.lookup(sessionId);
        if (sessionAci == null) {
            LOG.warning(() -> "gRPC menu RA unknown session activity: " + sessionId);
            return;
        }
        // Request event: session ACI via SleeEndpointPort (canonical hot path).
        publish(sessionId, eventFactory.createRequestEvent(sessionId, msisdn, ussdString));
        workerPool.submit(() -> doCall(sessionId, msisdn, ussdString, responseAci));
    }

    private void doCall(String sessionId, String msisdn, String ussdString,
                        ActivityContextInterface responseAci) {
        SleeEvent responseEvent;
        try {
            GrpcMenuUpstreamResult resp = upstream.resolveMenu(msisdn, ussdString, sessionId);
            responseEvent = eventFactory.createResponseEvent(
                    resp.getSessionId(), resp.getStatus(), resp.getMenuText(), resp.getError());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "gRPC menu RA call failed for session=" + sessionId, t);
            responseEvent = eventFactory.createErrorResponseEvent(sessionId, t);
        }
        routeResponse(responseAci, responseEvent);
    }

    /**
     * Route a response event to the caller-supplied ACI via the live
     * container. Returns silently when the response ACI is {@code null}
     * (caller did not opt into separate response activity).
     */
    private void routeResponse(ActivityContextInterface responseAci, SleeEvent event) {
        if (responseAci == null) {
            return;
        }
        Object c = container();
        if (c instanceof com.microjainslee.core.MicroSleeContainer mc) {
            mc.routeEvent(event, responseAci);
            return;
        }
        LOG.warning(() -> "gRPC menu RA cannot route response to ACI — "
                + "no live MicroSleeContainer available; response event dropped");
    }
}
