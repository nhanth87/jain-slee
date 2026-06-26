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

/**
 * JAIN-SLEE 1.1 §10.8 — Profile Table handle.
 * <p>
 * Lightweight view onto a single profile table. Returned by
 * {@link ProfileFacility#getProfileTable(String)} and provides the read-side
 * operations needed to inspect / iterate profile rows without going through
 * the full facility.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface ProfileTable {

    /** @return the logical name of this profile table. */
    String getProfileTableName();

    /** @return the number of profile rows currently in the table. */
    int getProfileCount();

    /**
     * Look up a single row by its profile (primary-key) name.
     *
     * @param profileName the row's primary key
     * @return the local object, or {@code null} when no row with that name exists
     */
    ProfileLocalObject getProfile(String profileName);

    /**
     * @return a snapshot of all profile rows in the table. The returned
     *         collection is independent of subsequent inserts/removes on
     *         this table.
     */
    Collection<ProfileLocalObject> getProfiles();

    /**
     * @param profileName the row's primary key
     * @return {@code true} when the table contains a row with that name
     */
    boolean containsProfile(String profileName);
}