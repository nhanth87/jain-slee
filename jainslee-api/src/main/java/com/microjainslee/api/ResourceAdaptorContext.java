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
}
