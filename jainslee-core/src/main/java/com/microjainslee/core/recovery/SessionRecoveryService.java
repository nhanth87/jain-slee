/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.recovery;

import java.util.Optional;

/**
 * Sprint S7 - Re-creation &amp; Replay on a Dead Slot.
 *
 * <p>Holds a bounded LRU cache of {@link RecoverySnapshot}s for recently
 * removed SBB entities. When the {@code EventRouter} detects a missing
 * entity (Gap-SR-1), it calls {@link #tryRehydrateAndDeliver} to
 * reconstruct the SBB from the last known snapshot and re-dispatch the
 * pending event.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface SessionRecoveryService {

    /**
     * Default cap - 64 Ki snapshots, enough to cover any reasonable
     * per-JVM working set.
     */
    int DEFAULT_MAX_SNAPSHOTS = 65_536;

    /**
     * Record a snapshot for an entity that is about to be released.
     * Idempotent on the same {@code entityId}: registering a second
     * snapshot replaces the previous one.
     */
    void registerSnapshot(RecoverySnapshot snapshot);

    /** Read-only lookup; does NOT remove the snapshot. */
    Optional<RecoverySnapshot> getSnapshot(String entityId);

    /**
     * Atomic lookup-and-remove. Returns the snapshot for {@code entityId}
     * if present, or {@link Optional#empty()} when no snapshot is cached.
     */
    Optional<RecoverySnapshot> consumeSnapshot(String entityId);

    /**
     * Core recovery method - called by {@code EventRouter} when
     * {@code pool.findEntity(sbbId)} returns {@code null}.
     *
     * <p>Contract:
     * <ul>
     *   <li>Returns {@code true} - event was successfully re-dispatched.</li>
     *   <li>Returns {@code false} - no snapshot, or callback could not
     *       reconstruct (caller MUST drop the event).</li>
     * </ul>
     *
     * <p>The {@code REHYDRATING} {@link ThreadLocal} guard is set for
     * the duration of this call so a re-dispatched event that itself
     * races against a missing entity cannot recurse infinitely (R10).
     */
    boolean tryRehydrateAndDeliver(String sbbId,
                                   Object event,
                                   com.microjainslee.api.ActivityContextInterface aci,
                                   com.microjainslee.api.SleeEventHandler handler);

    /** Total snapshots currently held in the LRU cache (diagnostics only). */
    int activeSnapshotCount();

    /**
     * Callback implemented by {@code MicroSleeContainer}. Keeps
     * {@code SessionRecoveryService} free of container internals.
     */
    interface RehydrateCallback {
        /**
         * Materialize a fresh SBB entity from the supplied snapshot,
         * attach it to every ACI named in the snapshot, and register it
         * with the kernel under {@code snapshot.entityId()}. Returns
         * {@code true} when the entity is ready for delivery,
         * {@code false} otherwise.
         */
        boolean reconstructFromSnapshot(RecoverySnapshot snapshot);
    }
}
