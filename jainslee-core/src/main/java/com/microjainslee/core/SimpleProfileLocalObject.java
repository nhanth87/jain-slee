/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;

/**
 * Minimal {@link ProfileLocalObject} implementation backed by a single
 * {@link Profile} instance.
 * <p>
 * The local object remains bound to the same {@link Profile} reference
 * it was constructed with; if the row is later removed from its table
 * via {@link InMemoryProfileTable#remove(String)}, the returned
 * {@link #getProfile()} continues to return the in-memory snapshot
 * (caller's responsibility to detect a stale row, e.g. via
 * {@link #getProfileID()} + a fresh {@link InMemoryProfileFacility#getProfile(ProfileID)}).
 *
 * @author Tran Nhan (nhanth87)
 */
public final class SimpleProfileLocalObject implements ProfileLocalObject {

    private final Profile profile;
    private final String tableName;

    public SimpleProfileLocalObject(Profile profile, String tableName) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        this.profile = profile;
        this.tableName = tableName;
    }

    /** {@inheritDoc} */
    @Override
    public Profile getProfile() {
        return profile;
    }

    /** {@inheritDoc} */
    @Override
    public ProfileID getProfileID() {
        ProfileID id = profile.getProfileID();
        if (id != null) {
            return id;
        }
        // Profile was not bound via bindProfile(); fall back to a synthetic
        // id that still uniquely references the table row for callers
        // that just want a non-null ProfileID.
        return new ProfileID(tableName, "?");
    }

    /** {@inheritDoc} */
    @Override
    public String getProfileTableName() {
        ProfileID id = profile.getProfileID();
        return id != null ? id.getProfileTableName() : tableName;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReadOnly() {
        // micro-jainslee does not currently expose a read-only profile
        // mode; the in-memory store accepts writes unconditionally.
        return false;
    }
}