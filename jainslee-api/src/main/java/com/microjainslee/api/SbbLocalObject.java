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
 * JAIN-SLEE 1.1 §5.5 — SBB Local Object interface.
 * <p>
 * Base interface of every SBB local interface. The SLEE supplies the
 * concrete implementation; user-defined SBB local interfaces simply
 * extend this type and add their own business methods.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface SbbLocalObject {

    /**
     * Obtain the SBB object currently bound to this SBB entity.
     *
     * @return the SBB object
     */
    Sbb getSbb();

    /**
     * Obtain the identifier of the SBB component that owns this entity.
     *
     * @return the SBB id
     */
    SbbID getSbbID();

    /**
     * Returns the invocation priority for this SBB entity (0–9 in micro-jainslee).
     * <p>
     * Non-spec helper retained for micro-jainslee internal use. Prefer the
     * spec-aligned {@link #getSbbPriority()} on JAIN SLEE 1.1 callers.
     *
     * @return the priority, where higher values mean higher priority
     */
    int getPriority();

    /**
     * Non-spec helper retained for micro-jainslee internal use to mutate the
     * value returned by {@link #getPriority()}. Prefer the spec-aligned
     * {@link #setSbbPriority(byte)} on JAIN SLEE 1.1 callers.
     *
     * @param priority new priority value
     */
    void setPriority(int priority);

    /**
     * Removes this SBB entity and detaches it from any activity contexts.
     * <p>
     * JAIN-SLEE 1.1 §5.5 — implementations must mark the current transaction
     * for rollback and throw {@link TransactionRolledbackLocalException}
     * when the entity is invalid.
     *
     * @throws TransactionRolledbackLocalException if invoked on an invalid entity
     * @throws TransactionRequiredLocalException   if invoked without an active transaction
     * @throws SLEEException                       for system-level failures
     */
    void remove() throws TransactionRolledbackLocalException,
            TransactionRequiredLocalException,
            SLEEException;

    /**
     * Returns {@code true} when this SBB entity has been removed.
     *
     * @return {@code true} if the entity has been removed
     */
    boolean isRemoved();

    /**
     * Runs {@code action} on the owning SBB entity thread when the container
     * supports entity pooling; otherwise runs inline on the caller thread.
     *
     * @param action the work to run on the entity's thread
     */
    default void invokeLocally(Runnable action) {
        action.run();
    }

    /**
     * JAIN-SLEE 1.1 §5.5.2 — set the event delivery priority of the target
     * SBB entity relative to its siblings.
     * <p>
     * Implemented as a default that delegates to the existing
     * {@link #setPriority(int)} helper so legacy implementations keep working.
     *
     * @param priority new priority in the range {@code -128..127}
     * @throws TransactionRolledbackLocalException if invoked on an invalid entity
     * @throws TransactionRequiredLocalException   if invoked without an active transaction
     * @throws SLEEException                       for system-level failures
     */
    default void setSbbPriority(byte priority)
            throws TransactionRolledbackLocalException,
                   TransactionRequiredLocalException,
                   SLEEException {
        setPriority(priority);
    }

    /**
     * JAIN-SLEE 1.1 §5.5.2 — read the event delivery priority of the target
     * SBB entity. Implemented as a default that delegates to the existing
     * {@link #getPriority()} helper.
     *
     * @return the current priority in the range {@code -128..127}
     * @throws TransactionRolledbackLocalException if invoked on an invalid entity
     * @throws TransactionRequiredLocalException   if invoked without an active transaction
     * @throws SLEEException                       for system-level failures
     */
    default byte getSbbPriority()
            throws TransactionRolledbackLocalException,
                   TransactionRequiredLocalException,
                   SLEEException {
        return (byte) getPriority();
    }

    /**
     * JAIN-SLEE 1.1 §5.5.1 — determine whether two SBB local objects refer to
     * the same underlying SBB entity.
     * <p>
     * Implemented by comparing {@link SbbID#getId()} and rejecting any
     * reference whose {@link #isRemoved()} returns {@code true}. Concrete
     * implementations are free to override with stronger equality.
     *
     * @param obj the other SBB local object to compare against
     * @return {@code true} when both references point at the same entity
     * @throws TransactionRequiredLocalException if invoked without an active transaction
     * @throws SLEEException                     for system-level failures
     */
    default boolean isIdentical(SbbLocalObject obj)
            throws TransactionRequiredLocalException,
                   SLEEException {
        if (obj == null || obj.isRemoved() || this.isRemoved()) {
            return false;
        }
        SbbID mine = this.getSbbID();
        SbbID theirs = obj.getSbbID();
        if (mine == null || theirs == null) {
            return false;
        }
        String myId = mine.getId();
        String theirId = theirs.getId();
        if (myId == null || theirId == null) {
            return false;
        }
        return myId.equals(theirId);
    }
}