/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.ra;

import com.example.ussddemo.embedded.UssdSessionStore;
import com.example.ussddemo.events.HttpUssdBeginEvent;
import com.example.ussddemo.grpc.proto.MenuResponse;
import com.example.ussddemo.events.GrpcMenuRequestEvent;
import com.example.ussddemo.events.GrpcMenuResponseEvent;
import com.microjainslee.api.ActivityContextHandle;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;
import com.microjainslee.api.SleeEndpointPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * gRPC menu RA. The child {@code GrpcClientSbb} calls
 * {@link #requestMenu(String, String, String, ActivityContextInterface)};
 * the RA performs the upstream unary call asynchronously and fires
 * {@link GrpcMenuResponseEvent} back on the supplied session ACI.
 */
public final class GrpcMenuResourceAdaptor implements ResourceAdaptor {

    private static final Logger LOG = LogManager.getLogger(GrpcMenuResourceAdaptor.class);

    private ResourceAdaptorContext context;
    private GrpcMenuClient client;
    private ExecutorService workerPool;

    public void setGrpcMenuClient(GrpcMenuClient client) {
        this.client = client;
    }

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
    }

    @Override
    public void raConfigure() {
        workerPool = Executors.newVirtualThreadPerTaskExecutor();
        LOG.info("gRPC menu RA configured");
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
        context = null;
    }

    public void requestMenu(String sessionId, String msisdn, String ussdString,
                            ActivityContextInterface responseAci) {
        if (context == null || client == null) {
            LOG.warn("gRPC RA not ready for requestMenu session={}", sessionId);
            return;
        }
        SleeEndpointPort endpoint = context.getSleeEndpointPort();
        GrpcMenuActivity activity = new GrpcMenuActivity(sessionId, msisdn, ussdString);
        ActivityContextHandle handle = context.createActivityContextHandle(activity);
        endpoint.startActivity(handle, activity);
        endpoint.fireEvent(handle, new GrpcMenuRequestEvent(sessionId, msisdn, ussdString));
        workerPool.submit(() -> doCall(activity, responseAci));
    }

    private void doCall(GrpcMenuActivity activity, ActivityContextInterface responseAci) {
        MenuResponse resp;
        try {
            resp = client.resolveMenu(activity.getMsisdn(), activity.getUssdString(),
                    activity.getSessionId());
        } catch (Throwable t) {
            LOG.warn("gRPC RA call failed for session=" + activity.getSessionId(), t);
            activity.completeExceptionally(t);
            routeResponse(responseAci, new GrpcMenuResponseEvent(
                    activity.getSessionId(), "ERR", null,
                    t.getClass().getSimpleName() + ": " + t.getMessage()));
            return;
        }
        activity.complete();
        routeResponse(responseAci, new GrpcMenuResponseEvent(
                resp.getSessionId(), resp.getStatus(), resp.getMenuText(), resp.getError()));
    }

    private void routeResponse(ActivityContextInterface responseAci, GrpcMenuResponseEvent event) {
        MicroSleeContainer container = container();
        if (container == null || responseAci == null) {
            LOG.warn("Cannot route gRPC response for session={}", event.getSessionId());
            return;
        }
        container.routeEvent(event, responseAci);
    }

    private MicroSleeContainer container() {
        if (context instanceof RaBootstrapContextImpl) {
            return ((RaBootstrapContextImpl) context).getContainer();
        }
        return null;
    }
}
