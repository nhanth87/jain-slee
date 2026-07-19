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
 * JAIN-SLEE 1.1 §10.12 — Profile Updated Event (Phase 1, Contract C5).
 * <p>
 * Emitted after a CMP field write on a profile row in a table that has events
 * enabled via {@link ProfileFacility#enableEvents(String, ProfileEventSink)}.
 *
 * <p>The coalescing queue (per {@code ProfileID}) may collapse multiple rapid
 * writes into a single event notification carrying the name of the
 * <em>last</em> field mutated before the drain picked up the entry. Callers
 * that need the final field value should re-read from the facility — the hot
 * store is always authoritative.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileUpdatedEvent implements SleeEvent {

    private final ProfileID profileID;
    /** Name of the last field mutated before this event was coalesced. */
    private final String fieldName;
    private final long timestampMs;

    /**
     * @param profileID   identity of the updated profile row
     * @param fieldName   name of the (last) CMP field that was written
     * @param timestampMs wall-clock time of the write (milliseconds since epoch)
     */
    public ProfileUpdatedEvent(ProfileID profileID, String fieldName, long timestampMs) {
        if (profileID == null) {
            throw new IllegalArgumentException("profileID is required");
        }
        this.profileID = profileID;
        this.fieldName = fieldName;
        this.timestampMs = timestampMs;
    }

    /** @return the identity of the profile that was updated */
    public ProfileID getProfileID() {
        return profileID;
    }

    /**
     * @return the name of the CMP field that triggered this event (coalesced:
     *         may represent multiple writes if several happened before drain)
     */
    public String getFieldName() {
        return fieldName;
    }

    /** @return wall-clock time of the write (milliseconds since epoch) */
    public long getTimestampMs() {
        return timestampMs;
    }

    @Override
    public String toString() {
        return "ProfileUpdatedEvent{profileID=" + profileID + ", field=" + fieldName
                + ", ts=" + timestampMs + '}';
    }
}
