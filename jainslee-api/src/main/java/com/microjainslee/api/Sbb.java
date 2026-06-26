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
 * JAIN-SLEE 1.1 §6.1 — SBB abstract class surface.
 * <p>
 * Defines the lifecycle and callback methods invoked by the SLEE on an
 * SBB object. All methods are provided as {@code default} no-ops so an
 * implementing SBB only has to override the callbacks it cares about.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface Sbb {

    /**
     * JAIN-SLEE 1.1 §6.1 — invoked when a new SBB entity is being created.
     */
    default void sbbCreate() throws CreateException {}

    /**
     * JAIN-SLEE 1.1 §6.3 — invoked after {@link #sbbCreate()} when the
     * SBB entity has been fully instantiated. The SLEE will call this
     * method on every new entity before any event handler is invoked.
     *
     * @throws CreateException if post-create initialization fails
     */
    default void sbbPostCreate() throws CreateException {}

    /**
     * JAIN-SLEE 1.1 §6.2 — invoked when the SBB object transitions from
     * the Pooled state to the Ready state.
     */
    default void sbbActivate() {}

    /**
     * JAIN-SLEE 1.1 §6.2 — invoked when the SLEE passivates the SBB object
     * and returns it to the pool.
     */
    default void sbbPassivate() {}

    /**
     * JAIN-SLEE 1.1 §6.2 — invoked when the SBB entity is removed.
     */
    default void sbbRemove() {}

    /**
     * JAIN-SLEE 1.1 §6.2 — invoked by the SLEE to load persistent CMP state
     * into the SBB object before it services an event.
     */
    default void sbbLoad() {}

    /**
     * JAIN-SLEE 1.1 §6.2 — invoked by the SLEE to flush transient state
     * back to the persistent CMP store.
     */
    default void sbbStore() {}

    /**
     * JAIN-SLEE 1.1 §6.9 — exception callback invoked when a mandatory
     * transactional method throws a {@link RuntimeException}.
     *
     * @param e    the exception thrown by the SLEE-invoked method
     * @param event the event that was being handled, or {@code null}
     * @param aci   the activity context that delivered the event, or {@code null}
     */
    default void sbbExceptionThrown(Exception e, Object event, ActivityContextInterface aci) {}

    /**
     * JAIN-SLEE 1.1 §6.2 — invoked exactly once before the SBB object is used
     * to service an SBB entity. Stores a reference to the {@link SbbContext}
     * the SBB will use to access SLEE facilities.
     *
     * @param context the SBB context for this SBB object
     */
    default void setSbbContext(SbbContext context) {}

    /**
     * JAIN-SLEE 1.1 §6.2 — invoked when the SLEE is about to release the
     * SBB object back to the pool. SBBs must release all references that
     * were acquired in {@link #setSbbContext(SbbContext)} to allow
     * garbage collection of the pooled object.
     */
    default void unsetSbbContext() {}

    /**
     * JAIN-SLEE 1.1 §6.10.1 — transaction-rolled-back callback invoked
     * by the SLEE after a transaction used for a SLEE-originated
     * invocation has been rolled back.
     *
     * @param context descriptor of the rolled-back transaction
     */
    default void sbbRolledBack(RolledBackContext context) {}
}