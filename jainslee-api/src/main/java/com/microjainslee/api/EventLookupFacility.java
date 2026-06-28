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
 * JAIN-SLEE 1.1 §15 — Event Lookup Facility.
 * <p>
 * Resolves an {@link EventTypeId} (the spec's name+vendor+version
 * triplet) to a {@link FireableEventType} that the RA can then hand to
 * {@code SleeEndpoint.fireEvent}. RAs call this during {@code raActive()}
 * to bind their event vocabulary to the types registered in the SLEE.
 */
public interface EventLookupFacility {

    FireableEventType getFireableEventType(EventTypeId eventTypeId);

    /**
     * @return all event types currently registered with the SLEE.
     */
    Iterable<EventTypeId> getAllEventTypeIds();
}
