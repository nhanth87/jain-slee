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
 * JAIN-SLEE 1.1 §13 — full SleeEndpoint surface exposed to Resource
 * Adaptors through {@link ResourceAdaptorContext}.
 * <p>
 * Distinct from the slim {@link SleeEndpointPort} used by {@link
 * com.microjainslee.ra.spi.AbstractResourceAdaptor}: the port is the
 * 3-method bridge (start/end/fire) used by embedded protocol stacks,
 * while the {@code SleeEndpoint} is the spec-compliant surface used
 * by full-blown RAs to manage activity lifecycles and fire typed
 * events. See
 * {@code com.microjainslee.ra.SleeEndpointImpl} for the canonical
 * implementation.
 */
public interface SleeEndpoint {

    /**
     * Inform the SLEE that a new RA-side activity has started.
     *
     * @throws ActivityAlreadyExistsException when a handle with the
     *         same id is already active.
     */
    void activityStarted(ActivityHandle handle)
            throws ActivityAlreadyExistsException;

    void activityEnded(ActivityHandle handle);

    void fireEvent(ActivityHandle handle,
                   Object event,
                   Address address,
                   FireableEventType eventType)
            throws UnrecognizedActivityException,
                   FiredUnrecognizedEventException,
                   IllegalStateException;

    /**
     * Notify the SLEE that the RA has drained its in-flight work and
     * is safe to transition from STOPPING to INACTIVE.
     */
    void stopComplete();
}
