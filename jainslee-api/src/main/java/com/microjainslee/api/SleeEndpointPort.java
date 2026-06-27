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
 * JAIN-SLEE 1.1 §11 — minimal {@code SleeEndpoint} surface for embedded RAs.
 * Protocol stacks fire events and start/end activities through this port
 * instead of calling {@code MicroSleeContainer.routeEvent} directly.
 */
public interface SleeEndpointPort {

    /**
     * Start a new activity and bind it to the given handle.
     *
     * @return the activity context interface for the started activity
     */
    ActivityContextInterface startActivity(ActivityContextHandle handle, Object activity);

    /**
     * End an activity previously started through this endpoint.
     */
    void endActivity(ActivityContextHandle handle);

    /**
     * Fire an event into the SLEE event router for the activity identified by
     * {@code handle}. Non-blocking — returns after the event is published to
     * the Disruptor ring buffer.
     */
    void fireEvent(ActivityContextHandle handle, SleeEvent event);
}
