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
 * JAIN-SLEE 1.1 §6 — Event Type metadata resolved through the
 * {@link EventLookupFacility}.
 */
public interface EventType {

    EventTypeId getId();

    String getName();

    String getVendor();

    String getVersion();

    /**
     * @return the Java class bound to this event type, when known.
     */
    Class<?> getEventClass();
}
