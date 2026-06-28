/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §11 — Resource Adaptor Context interface.
 * Provides context for Resource Adaptors.
 */
public interface ResourceAdaptorContext {
    void setResourceAdaptor(ResourceAdaptor ra);

    /**
     * Create a new activity context handle for the given RA activity object.
     */
    ActivityContextHandle createActivityContextHandle(Object activity);

    /**
     * Look up the handle previously created for an RA activity object.
     *
     * @return the handle, or {@code null} if none was registered
     */
    ActivityContextHandle getActivityContextHandle(Object activity);

    /**
     * @return the {@link SleeEndpointPort} for firing events into the SLEE,
     *         or {@code null} when not available on this context implementation.
     */
    default SleeEndpointPort getSleeEndpointPort() {
        return null;
    }

    /**
     * Optional escape hatch for RAs that need to route an event onto an
     * already-resolved {@link com.microjainslee.api.ActivityContextInterface}
     * — e.g. to publish a response event onto a different ACI than the
     * request event used. Mirrors
     * {@code MicroSleeContainer.routeEvent(SleeEvent, ActivityContextInterface)}.
     * <p>
     * Default {@code null} — the canonical {@code SleeEndpointPort} surface
     * is {@link SleeEndpointPort#fireEvent} (handle-based) which already
     * covers the common case. Implementations that have a back-reference
     * to the live container (e.g. {@code RaBootstrapContextImpl}) return it
     * from this method; the {@code AbstractResourceAdaptor} base class
     * exposes it via a protected {@code container()} helper for subclasses.
     */
    default Object getContainer() {
        return null;
    }
}
