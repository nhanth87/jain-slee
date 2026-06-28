/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.spi;

import com.microjainslee.api.ActivityContextHandle;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;
import com.microjainslee.api.SimpleActivityContextHandle;
import com.microjainslee.api.SleeEndpointPort;
import com.microjainslee.api.SleeEvent;

/**
 * Optional base class for RAs. Subclasses implement lifecycle hooks; hot-path
 * event delivery uses {@link #publish(String, SleeEvent)} on {@link SleeEndpointPort}.
 */
public abstract class AbstractResourceAdaptor implements ResourceAdaptor {

    private ResourceAdaptorContext context;

    @Override
    public final void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
        if (context != null) {
            context.setResourceAdaptor(this);
        }
        onContextSet(context);
    }

    /**
     * Spec §11.3 — last lifecycle call. Delegates to {@link #onContextUnset()}
     * so subclasses can run custom cleanup, then nulls the context field.
     * <p>
     * Perfect Core S5 — when the wired context is a
     * {@link com.microjainslee.ra.ResourceAdaptorContextImpl} the
     * teardown also drives the state machine to INACTIVE (if it was
     * still ACTIVE/STOPPING). This mirrors what
     * {@code MicroSleeContainer.stopRA(entityName)} does in the
     * kernel-side path; the SPI base class performs the same stop
     * sequence locally so direct callers that drive
     * {@code unsetResourceAdaptorContext()} directly (no kernel
     * in sight) still get a clean shutdown.
     * <p>
     * The {@code final} modifier is dropped so this method is the public
     * override point (matching the spec semantics that the SLEE calls this
     * directly during RA teardown). {@link #raUnconfigure()} now delegates
     * here rather than duplicating the cleanup logic.
     */
    @Override
    public void unsetResourceAdaptorContext() {
        // Drive any wired RA state machine to INACTIVE before we
        // discard the context reference. This is idempotent: the
        // state machine's stopComplete() is a no-op when the state
        // is not STOPPING, and the container-side stopRA() will
        // already have done this for entities it manages.
        if (context instanceof com.microjainslee.ra.ResourceAdaptorContextImpl rci) {
            com.microjainslee.api.SleeEndpoint endpoint = rci.getSleeEndpoint();
            if (endpoint != null) {
                try {
                    endpoint.stopComplete();
                } catch (RuntimeException ignored) {
                    // best effort — context is going away anyway
                }
            }
        }
        onContextUnset();
        this.context = null;
    }

    protected void onContextSet(ResourceAdaptorContext context) {
    }

    protected void onContextUnset() {
    }

    protected final ResourceAdaptorContext context() {
        return context;
    }

    protected final SleeEndpointPort endpoint() {
        if (context == null) {
            throw new IllegalStateException("ResourceAdaptorContext not set");
        }
        SleeEndpointPort port = context.getSleeEndpointPort();
        if (port == null) {
            throw new IllegalStateException("SleeEndpointPort not available on context");
        }
        return port;
    }

    /**
     * Optional back-reference to the live micro-jainslee container for RAs
     * that need to route events onto a specific
     * {@link com.microjainslee.api.ActivityContextInterface} instead of a
     * string id (e.g. request/response over different ACIs). Returns
     * {@code null} when the context does not expose a container (e.g. tests
     * using a stub). Callers should null-check.
     */
    protected final Object container() {
        if (context == null) {
            return null;
        }
        return context.getContainer();
    }

    /**
     * Fire an event on a named session/dialog activity handle.
     */
    protected final void publish(String activityId, SleeEvent event) {
        ActivityContextHandle handle = new SimpleActivityContextHandle(activityId);
        endpoint().fireEvent(handle, event);
    }

    @Override
    public void raUnconfigure() {
        unsetResourceAdaptorContext();
    }
}
