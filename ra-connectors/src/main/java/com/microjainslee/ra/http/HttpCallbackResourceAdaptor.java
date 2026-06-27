/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.http;

import com.microjainslee.api.ActivityContextHandle;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;
import com.microjainslee.api.SleeEndpointPort;
import com.microjainslee.api.SleeEvent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JAIN-SLEE 1.1 §11 — HTTP callback ingress RA.
 *
 * <p>Receives protocol events from an HTTP transport and fires them into the
 * SLEE through {@link SleeEndpointPort}. Designed for maximum throughput:
 * no blocking on the caller thread beyond map lookup and Disruptor publish.
 */
public final class HttpCallbackResourceAdaptor implements ResourceAdaptor {

    private volatile ResourceAdaptorContext context;
    private final ConcurrentHashMap<String, ActivityContextHandle> sessions =
            new ConcurrentHashMap<String, ActivityContextHandle>();

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
        if (context != null) {
            context.setResourceAdaptor(this);
        }
    }

    @Override
    public void raConfigure() {
    }

    @Override
    public void raActive() {
    }

    @Override
    public void raStopping() {
    }

    @Override
    public void raInactive() {
        sessions.clear();
    }

    @Override
    public void raUnconfigure() {
        context = null;
    }

    /**
     * Begin a USSD/HTTP session: start activity + fire initial event.
     */
    public void onHttpBegin(String sessionId, SleeEvent initialEvent) {
        Object activity = new HttpSessionActivity(sessionId);
        ActivityContextHandle handle = context.createActivityContextHandle(activity);
        sessions.put(sessionId, handle);
        requireEndpoint().fireEvent(handle, initialEvent);
    }

    /**
     * End a session and release the activity handle.
     */
    public void onHttpEnd(String sessionId) {
        ActivityContextHandle handle = sessions.remove(sessionId);
        if (handle != null) {
            requireEndpoint().endActivity(handle);
        }
    }

    /**
     * Fire a follow-up event on an existing session.
     */
    public void onHttpEvent(String sessionId, SleeEvent event) {
        ActivityContextHandle handle = sessions.get(sessionId);
        if (handle == null) {
            throw new IllegalStateException("Unknown HTTP session: " + sessionId);
        }
        requireEndpoint().fireEvent(handle, event);
    }

    private SleeEndpointPort requireEndpoint() {
        if (context == null || context.getSleeEndpointPort() == null) {
            throw new IllegalStateException("SleeEndpointPort not available on RA context");
        }
        return context.getSleeEndpointPort();
    }

    /** RA-side activity token bound to an HTTP session id. */
    public static final class HttpSessionActivity {
        private final String sessionId;

        public HttpSessionActivity(String sessionId) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        }

        public String getSessionId() {
            return sessionId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof HttpSessionActivity)) {
                return false;
            }
            return sessionId.equals(((HttpSessionActivity) obj).sessionId);
        }

        @Override
        public int hashCode() {
            return sessionId.hashCode();
        }
    }
}
