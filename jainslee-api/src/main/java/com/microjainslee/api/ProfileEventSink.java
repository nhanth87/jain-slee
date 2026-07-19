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
 * Phase 1 — Profile event consumer interface (Contract C5, §10.12).
 * <p>
 * Callers register a {@code ProfileEventSink} per profile table via
 * {@link ProfileFacility#enableEvents(String, ProfileEventSink)} to receive
 * asynchronous notifications about profile lifecycle changes: rows created,
 * CMP fields mutated, and rows removed.
 *
 * <p><b>Threading contract:</b> callbacks are invoked from a virtual thread
 * maintained by the facility. Implementations must be thread-safe. Callbacks
 * must not call back into the facility with blocking operations; doing so risks
 * deadlock on the drain thread. Profile reads are safe; profile writes from
 * inside a callback are allowed but not recommended — use them sparingly and
 * never perform long-running IO.
 *
 * <p>Events are delivered with at-most-once semantics per coalescing window:
 * multiple rapid writes to the same profile between drain cycles are collapsed
 * into a single {@link ProfileUpdatedEvent}. The hot store ({@link ProfileFacility})
 * is always the authoritative source of truth.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface ProfileEventSink {

    /**
     * Called after a new profile row has been successfully created.
     *
     * @param event carries the profile identity and creation timestamp
     */
    void onProfileAdded(ProfileAddedEvent event);

    /**
     * Called after one or more CMP fields on an existing profile row have
     * been mutated (coalesced: one notification per drain cycle per row).
     *
     * @param event carries the profile identity, last-written field name, and timestamp
     */
    void onProfileUpdated(ProfileUpdatedEvent event);

    /**
     * Called after a profile row has been removed.
     *
     * @param event carries the profile identity and removal timestamp
     */
    void onProfileRemoved(ProfileRemovedEvent event);
}
