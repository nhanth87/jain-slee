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
     * The {@code final} modifier is dropped so this method is the public
     * override point (matching the spec semantics that the SLEE calls this
     * directly during RA teardown). {@link #raUnconfigure()} now delegates
     * here rather than duplicating the cleanup logic.
     */
    @Override
    public void unsetResourceAdaptorContext() {
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
