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
 * JAIN-SLEE 1.1 §6.10.1.1 — RolledBackContext interface.
 * <p>
 * Passed to {@link Sbb#sbbRolledBack(RolledBackContext)} so the SBB can
 * introspect the transaction that has just been rolled back by the SLEE.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface RolledBackContext {

    /**
     * §6.10.1.1 — the event object that was being delivered when the
     * transaction rolled back, or {@code null} if no event was being
     * delivered (e.g. the rollback happened during {@code sbbCreate} or
     * {@code sbbRemove}).
     *
     * @return the event or {@code null}
     */
    Object getEvent();

    /**
     * §6.10.1.1 — the {@link ActivityContextInterface} that delivered the
     * event to the SBB, or {@code null} if the rolled-back transaction did
     * not deliver an event.
     *
     * @return the activity context or {@code null}
     */
    ActivityContextInterface getActivityContextInterface();

    /**
     * §6.10.1.1 — returns {@code true} when the rolled-back transaction
     * included a SLEE-originated logical cascading-removal invocation
     * (see §9.8.1). SBBs typically use this to skip resource cleanup that
     * the cascading removal has already taken care of.
     *
     * @return {@code true} when the rollback was due to a cascading remove
     */
    boolean isRemoveRolledBack();
}