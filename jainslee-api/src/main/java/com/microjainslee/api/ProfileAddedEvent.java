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
 * JAIN-SLEE 1.1 §10.12 — Profile Added Event (Phase 1, Contract C5).
 * <p>
 * Emitted by the facility after a new profile row has been successfully created
 * in a table that has events enabled via
 * {@link ProfileFacility#enableEvents(String, ProfileEventSink)}.
 *
 * <p>Events are published asynchronously via a coalescing queue drained by
 * a virtual thread; they are never published directly from the mutator thread
 * to avoid blocking or deadlocking the caller.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileAddedEvent implements SleeEvent {

    private final ProfileID profileID;
    private final long timestampMs;

    /**
     * @param profileID   identity of the newly created profile row
     * @param timestampMs wall-clock time at creation (milliseconds since epoch)
     */
    public ProfileAddedEvent(ProfileID profileID, long timestampMs) {
        if (profileID == null) {
            throw new IllegalArgumentException("profileID is required");
        }
        this.profileID = profileID;
        this.timestampMs = timestampMs;
    }

    /** @return the identity of the profile that was added */
    public ProfileID getProfileID() {
        return profileID;
    }

    /** @return wall-clock time at creation (milliseconds since epoch) */
    public long getTimestampMs() {
        return timestampMs;
    }

    @Override
    public String toString() {
        return "ProfileAddedEvent{profileID=" + profileID + ", ts=" + timestampMs + '}';
    }
}
