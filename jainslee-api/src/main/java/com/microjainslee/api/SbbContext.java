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
 * JAIN-SLEE 1.1 §6 — SBB Context interface.
 * <p>
 * Provides an SBB object with access to SLEE facilities (timer, profiles,
 * tracing, etc.). The mandatory methods remain abstract; extension methods
 * added in JAIN SLEE 1.1 are exposed as {@code default} implementations
 * so existing SBBs are unaffected.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface SbbContext {

    /**
     * JAIN-SLEE 1.1 §6 — identifier of the Service owning this SBB.
     */
    ServiceID getService();

    /**
     * JAIN-SLEE 1.1 §6.4.1 — SBB local object for the currently bound SBB entity.
     *
     * @throws TransactionRequiredLocalException when invoked outside a tx
     * @throws IllegalStateException             when no SBB entity is bound
     * @throws SLEEException                     for system-level failures
     */
    SbbLocalObject getSbbLocalObject()
            throws TransactionRequiredLocalException, SLEEException;

    /**
     * JAIN-SLEE 1.1 §6 — timer facility access.
     */
    TimerPort getTimerFacility();

    /**
     * JAIN-SLEE 1.1 §6 — activity context naming facility access.
     */
    ActivityContextNamingFacility getActivityContextNamingFacility();

    /**
     * JAIN-SLEE 1.1 §6 — obtain a tracer by name.
     */
    TracePort getTracer(String tracerName);

    /**
     * JAIN-SLEE 1.1 §6 — usage facility access.
     */
    UsagePort getUsageFacility();

    /**
     * JAIN-SLEE 1.1 §11 — alarm facility access.
     * <p>
     * micro-jainslee exposes this as a default that returns {@code null}
     * because the underlying alarm sink is pluggable; concrete contexts
     * must override when an alarm backend is configured.
     *
     * @return the alarm facility, or {@code null} when no alarm backend
     *         is installed
     */
    default AlarmFacility getAlarmFacility() {
        return null;
    }

    /**
     * JAIN-SLEE 1.1 §10 — profile facility access.
     * <p>
     * micro-jainslee exposes this as a default that returns {@code null}
     * because the underlying profile backend is pluggable; concrete
     * contexts must override when a profile store is configured.
     *
     * @return the profile facility port, or {@code null} when no profile
     *         backend is installed
     */
    default ProfileTablePort getProfileFacility() {
        return null;
    }

    /**
     * JAIN-SLEE 1.1 §6.10.2 — mark the current transaction for rollback.
     * <p>
     * Default implementation is a no-op so that non-transactional contexts
     * remain usable; transactional contexts must override.
     */
    default void setRollbackOnly() {}

    /**
     * JAIN-SLEE 1.1 §6.10.3 — query whether the current transaction is
     * marked for rollback. Returns {@code false} by default.
     *
     * @return {@code true} when the transaction is marked for rollback
     */
    default boolean getRollbackOnly() {
        return false;
    }

    /**
     * JAIN-SLEE 1.1 §6.4 — activity context that names the SBB entity
     * itself (i.e. the SBB's own activity context, when defined).
     * <p>
     * Default returns {@code null}; concrete contexts must override when
     * the SBB declares an SBB Activity Context Interface.
     *
     * @return the SBB activity context, or {@code null} if undefined
     */
    default ActivityContextInterface getSbbActivityContext() {
        return null;
    }

    /**
     * JAIN-SLEE 1.1 §6 — set of activity contexts currently attached to
     * this SBB entity. Returns an empty set by default.
     *
     * @return the set of attached activity contexts (never {@code null})
     */
    default java.util.Set<ActivityContextInterface> getActivities() {
        return java.util.Collections.emptySet();
    }

    /**
     * JAIN-SLEE 1.1 §6 — mask future delivery of events with the given
     * fully qualified event type name. Default is a no-op.
     *
     * @param eventTypeFqn fully qualified event type identifier
     */
    default void maskEvent(String eventTypeFqn) {}

    /**
     * JAIN-SLEE 1.1 §8.5.3 — return the set of masked event types for the
     * SBB entity. Default returns an empty set.
     *
     * @return set of masked event-type identifiers (never {@code null})
     */
    default java.util.Set<String> getEventMask() {
        return java.util.Collections.emptySet();
    }

    /**
     * JAIN-SLEE 1.1 §6.4.3 — SBB component identifier of the SBB class
     * owning this context. Distinct from the SBB instance itself (which
     * the SBB code already has). Default returns a stub id derived from
     * the SBB class simple name so SBB code can always call this without
     * a null check.
     *
     * @return the SBB component id
     * @throws SLEEException for system-level failures
     */
    default SbbID getSbb() throws SLEEException {
        // Default: a stub id based on the SBB class. Subclasses should override
        // with the actual component id from the deployment descriptor.
        return new SbbID(getClass().getSimpleName());
    }
}