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

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * JAIN-SLEE 1.1 §10.14 — Profile Facility.
 * <p>
 * Top-level entry point for profile management. SBB code obtains this via
 * {@link SbbContext#getProfileFacility()} and uses it to look up profile
 * tables, create / remove / query profile rows.
 *
 * <p>micro-jainslee supplies an in-memory implementation
 * ({@code com.microjainslee.core.InMemoryProfileFacility}) for tests and
 * R&amp;D; production deployments plug a JPA / Redis-backed implementation
 * via {@code MicroSleeContainer.installProfileFacility(...)}.
 *
 * <p>This is the spec-aligned successor to the legacy
 * {@link ProfileTablePort} interface, which is retained as a thin
 * deprecated alias.
 *
 * <p><b>Phase 1 extensions (§10.5, §10.6, §10.8, §10.12, C4, C5, C7, C8):</b>
 * default profiles, secondary-index registration and query,
 * atomic counter operations, event opt-in, and synchronous flush.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface ProfileFacility {

    /**
     * Get a handle on a profile table by name.
     *
     * @param tableName name of the profile table
     * @return the profile table handle, or {@code null} when no table with
     *         that name exists
     */
    ProfileTable getProfileTable(String tableName);

    /**
     * Create a new profile row in the given table.
     *
     * @param tableName    name of an existing profile table
     * @param profileName  primary-key name for the new row
     * @param profileClass concrete {@link Profile} subclass used to instantiate
     *                     the row's CMP object (must extend
     *                     {@link ProfileAbstractCmp})
     * @return a {@link ProfileLocalObject} bound to the new row
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     * @throws ProfileAlreadyExistsException         if a row with this profile name already exists
     * @throws SLEEException                         for system-level failures
     */
    ProfileLocalObject createProfile(String tableName, String profileName,
                                     Class<? extends Profile> profileClass)
            throws UnrecognizedProfileTableNameException,
                   ProfileAlreadyExistsException,
                   SLEEException;

    /**
     * Look up an existing profile row by id.
     *
     * @param id profile identifier
     * @return the local object, or {@code null} when no such row exists
     */
    ProfileLocalObject getProfile(ProfileID id);

    /**
     * Remove a profile row.
     *
     * @param id profile identifier
     * @throws UnrecognizedProfileTableNameException if the underlying table does not exist
     * @throws SLEEException                         for system-level failures
     */
    void removeProfile(ProfileID id) throws UnrecognizedProfileTableNameException, SLEEException;

    /**
     * Provision a new profile table. Idempotent on a pre-existing table of
     * the same name.
     *
     * @param tableName logical name of the new table
     */
    void createProfileTable(String tableName);

    /**
     * Drop a profile table and all rows it contains. Idempotent: dropping a
     * non-existent table is a no-op.
     *
     * @param tableName logical name of the table to drop
     */
    void removeProfileTable(String tableName);

    /**
     * @return the set of currently provisioned profile table names (never {@code null})
     */
    Set<String> getProfileTableNames();

    // -----------------------------------------------------------------------
    // Phase 1 — Default profiles (§10.5)
    // -----------------------------------------------------------------------

    /**
     * Register a default-profile template for a table. Subsequent calls to
     * {@link #createFromDefault(String, String)} clone the current field values
     * of this profile instance into the newly created row.
     *
     * <p>The supplied {@code defaultProfile} must already be bound to this
     * table (i.e. it was previously returned by
     * {@link #createProfile(String, String, Class)}) so that field values can
     * be read from the hot store.
     *
     * @param tableName     the table to associate the default with
     * @param defaultProfile a profile instance (bound to {@code tableName})
     *                       whose current CMP field snapshot will be used as
     *                       the template for new rows
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     * @throws IllegalArgumentException              if {@code defaultProfile} is not bound
     *                                               to {@code tableName}
     */
    void setDefaultProfile(String tableName, Profile defaultProfile)
            throws UnrecognizedProfileTableNameException;

    /**
     * Create a new profile row by cloning the field snapshot of the table's
     * registered default profile.
     *
     * @param tableName   the table to create the row in
     * @param profileName primary-key name of the new row
     * @return a {@link ProfileLocalObject} bound to the new row
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     * @throws ProfileAlreadyExistsException         if a row with this name already exists
     * @throws IllegalStateException                 if no default profile has been registered
     *                                               for the table
     * @throws SLEEException                         for system-level failures
     */
    ProfileLocalObject createFromDefault(String tableName, String profileName)
            throws UnrecognizedProfileTableNameException,
                   ProfileAlreadyExistsException,
                   SLEEException;

    // -----------------------------------------------------------------------
    // Phase 1 — Secondary indexes (§10.6, §10.8)
    // -----------------------------------------------------------------------

    /**
     * Register a secondary index on {@code attributeName} for the given table.
     * The index is maintained incrementally on every subsequent
     * {@link Profile#setCmpField(String, Object)} write. Rows already
     * in the table at registration time are NOT back-filled; call
     * {@code registerIndex} before provisioning rows, or provision after.
     *
     * <p>Calling this method more than once for the same (table, attribute)
     * pair is idempotent.
     *
     * @param tableName     the table to index
     * @param attributeName the CMP field name to index
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     */
    void registerIndex(String tableName, String attributeName)
            throws UnrecognizedProfileTableNameException;

    /**
     * Find all profile rows in {@code tableName} whose CMP field
     * {@code attributeName} equals {@code value} (using
     * {@link Object#equals(Object)} semantics).
     *
     * <p>The attribute must have been registered with {@link #registerIndex}
     * prior to this call.
     *
     * @param tableName     the table to search
     * @param attributeName the indexed CMP field name
     * @param value         the value to match (may be {@code null} to find rows
     *                      with an explicit {@code null} / absent field)
     * @return a snapshot collection of matching local objects (never {@code null},
     *         may be empty)
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     * @throws IllegalStateException                 if {@code attributeName} has not been
     *                                               registered as an index (§10.6: no silent
     *                                               full-table scan)
     */
    Collection<ProfileLocalObject> findProfilesByAttribute(
            String tableName, String attributeName, Object value)
            throws UnrecognizedProfileTableNameException;

    // -----------------------------------------------------------------------
    // Phase 1 — Convenience query
    // -----------------------------------------------------------------------

    /**
     * Test whether a profile row exists without allocating a
     * {@link ProfileLocalObject}.
     *
     * @param id profile identifier
     * @return {@code true} when the row exists
     */
    boolean profileExists(ProfileID id);

    // -----------------------------------------------------------------------
    // Phase 1 — Atomic counter operations (C4)
    // -----------------------------------------------------------------------

    /**
     * Atomically add {@code delta} to the {@code long} field {@code field} of
     * the named profile and return the new value. The field is treated as
     * {@code 0L} when absent.
     *
     * <p>This is the mandatory mutation path for billing/usage counters per
     * contract C4. {@code get → compute → set} patterns race between
     * concurrent event handlers and MUST be replaced with this method.
     *
     * @param id    profile identifier
     * @param field CMP field name
     * @param delta value to add (negative for subtraction)
     * @return the new value after applying the delta
     * @throws ProfileNotFoundException              if the row does not exist
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     */
    long addToLong(ProfileID id, String field, long delta)
            throws ProfileNotFoundException, UnrecognizedProfileTableNameException;

    /**
     * Atomically compute a new field value using {@code fn} and store it,
     * returning the new value.
     *
     * @param id    profile identifier
     * @param field CMP field name
     * @param fn    pure function mapping old value → new value; receives
     *              {@code null} when the field is absent
     * @return the new value after applying {@code fn}
     * @throws ProfileNotFoundException              if the row does not exist
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     * @throws IllegalArgumentException              if {@code fn} returns a type not in the
     *                                               {@link ProfileFieldTypes} whitelist
     */
    Object updateField(ProfileID id, String field, UnaryOperator<Object> fn)
            throws ProfileNotFoundException, UnrecognizedProfileTableNameException;

    /**
     * Atomically compare-and-set a field value.
     *
     * @param id     profile identifier
     * @param field  CMP field name
     * @param expect current expected value (uses {@link Object#equals(Object)};
     *               pass {@code null} to match an absent field)
     * @param update new value to set when the compare succeeds
     * @return {@code true} when the field was updated, {@code false} when the
     *         expected value did not match
     * @throws ProfileNotFoundException              if the row does not exist
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     * @throws IllegalArgumentException              if {@code update} is not in the
     *                                               {@link ProfileFieldTypes} whitelist
     */
    boolean compareAndSetField(ProfileID id, String field, Object expect, Object update)
            throws ProfileNotFoundException, UnrecognizedProfileTableNameException;

    // -----------------------------------------------------------------------
    // Phase 1 — Profile lifecycle events (C5, §10.12)
    // -----------------------------------------------------------------------

    /**
     * Enable opt-in profile lifecycle events for {@code tableName}. After this
     * call, profile added, updated, and removed events for this table are
     * placed into the facility's coalescing queue and delivered to {@code sink}
     * from a virtual drain thread.
     *
     * <p>Calling {@code enableEvents} on a table that already has a sink
     * replaces the existing sink.
     *
     * @param tableName the table to enable events for
     * @param sink      the consumer to deliver events to (must not be {@code null})
     * @throws UnrecognizedProfileTableNameException if the table does not exist
     */
    void enableEvents(String tableName, ProfileEventSink sink)
            throws UnrecognizedProfileTableNameException;

    /**
     * Disable profile lifecycle events for {@code tableName}. Buffered but
     * not-yet-drained events are discarded.
     *
     * @param tableName the table to disable events for
     */
    void disableEvents(String tableName);

    // -----------------------------------------------------------------------
    // Phase 1 — Synchronous flush stub (Phase 2 will have real semantics)
    // -----------------------------------------------------------------------

    /**
     * Request a synchronous flush of any dirty profile state to the durable
     * store. In the current in-memory-only implementation this is a no-op.
     * Phase 2 will implement real write-behind flush semantics (C1, C6.2).
     *
     * @param timeout maximum time to wait for flush completion
     * @param unit    time unit for {@code timeout}
     */
    void flushSync(long timeout, TimeUnit unit);
}