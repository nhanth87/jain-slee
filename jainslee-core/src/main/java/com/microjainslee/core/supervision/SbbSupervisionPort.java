/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.supervision;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;

/**
 * Production P3 — kernel-side restart mechanics invoked by
 * {@link SbbSupervisor}. Implemented by {@code MicroSleeContainer} (inner
 * adapter); kept as a standalone interface so the supervision package has
 * zero compile-time edges into the rest of {@code jainslee-core}.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface SbbSupervisionPort {

    /**
     * Attempt a full supervised restart of the entity registered under
     * {@code sbbId}: capture live state (CMP fields, event mask, attached
     * activity-context names), force-remove the possibly-wedged entity —
     * abandoning its slot so the stuck thread can never be re-bound — then
     * recreate a fresh instance from the registered type pool, restore the
     * captured CMP state and re-attach every activity context.
     *
     * @return {@code true} when a fresh entity was registered and its
     *         activation was submitted; {@code false} when the entity is
     *         unknown, is a legacy (non-pooled) SBB, or the container is
     *         not STARTED.
     */
    boolean restartEntity(String sbbId);

    /**
     * Final action once restart attempts are exhausted: force-remove the
     * entity for good and raise a CRITICAL alarm on the container's
     * {@code AlarmFacility}. The SBB instance is NOT returned to its type
     * pool (a repeatedly-crashing instance must never poison fresh ones).
     */
    void giveUp(String sbbId, String reason);

    /**
     * M3 — re-route one event that was parked while a supervised restart
     * was in flight. Called by the supervisor after the fresh entity has
     * been registered and re-attached, once per parked event, in FIFO
     * order. Implementations should push the event through the normal
     * router path so it receives the full dispatch machinery
     * (transaction, MDC, event mask, failure reporting).
     *
     * <p>Default no-op so third-party implementations keep compiling.
     */
    default void replayEvent(SleeEvent event, ActivityContextInterface aci) {
        // no-op — pre-M3 ports have nowhere to send the event.
    }
}
