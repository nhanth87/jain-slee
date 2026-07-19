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
 * JAIN-SLEE 1.1 §10.12 — Profile Removed Event (Phase 1, Contract C5).
 * <p>
 * Emitted after a profile row has been removed from a table that has events
 * enabled via {@link ProfileFacility#enableEvents(String, ProfileEventSink)}.
 *
 * <p>This event is emitted synchronously by the removal path (no coalescing
 * needed — removal is a terminal operation) but still delivered via the
 * coalescing drain thread to preserve the non-blocking mutator contract (C5).
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileRemovedEvent implements SleeEvent {

    private final ProfileID profileID;
    private final long timestampMs;

    /**
     * @param profileID   identity of the profile row that was removed
     * @param timestampMs wall-clock time of removal (milliseconds since epoch)
     */
    public ProfileRemovedEvent(ProfileID profileID, long timestampMs) {
        if (profileID == null) {
            throw new IllegalArgumentException("profileID is required");
        }
        this.profileID = profileID;
        this.timestampMs = timestampMs;
    }

    /** @return the identity of the profile that was removed */
    public ProfileID getProfileID() {
        return profileID;
    }

    /** @return wall-clock time of removal (milliseconds since epoch) */
    public long getTimestampMs() {
        return timestampMs;
    }

    @Override
    public String toString() {
        return "ProfileRemovedEvent{profileID=" + profileID + ", ts=" + timestampMs + '}';
    }
}
