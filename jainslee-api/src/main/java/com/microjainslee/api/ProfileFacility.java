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

import java.util.Set;

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
}