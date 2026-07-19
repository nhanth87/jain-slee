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
import com.microjainslee.api.ProfileNotFoundException;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal {@link ProfileLocalObject} implementation backed by a single
 * {@link Profile} instance.
 *
 * <h3>Phase 1 — Contract C8 (stale local object detection)</h3>
 * <p>
 * Each row in {@link InMemoryProfileTable} owns one shared {@link AtomicBoolean}
 * liveness flag ({@code true} = row is alive). Every {@code SimpleProfileLocalObject}
 * created for that row holds a reference to the same flag. When
 * {@link InMemoryProfileTable#remove(String)} is called, the flag is set to
 * {@code false}. All outstanding local objects for that row then see
 * {@link #isInvalidated()} return {@code true}, and any access to
 * {@link #getProfile()}, {@link #getProfileID()}, or
 * {@link #getProfileTableName()} throws {@link ProfileNotFoundException}.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class SimpleProfileLocalObject implements ProfileLocalObject {

    private final Profile profile;
    private final String tableName;
    /**
     * Shared liveness flag: {@code true} while the row exists, {@code false}
     * after it has been removed. Shared across all LOs for the same row so
     * that one flag transition propagates to all holders atomically.
     */
    private final AtomicBoolean rowAlive;

    /**
     * Primary constructor: accepts the shared row-liveness flag from the table.
     *
     * @param profile   the profile instance backing this local object
     * @param tableName the profile table name
     * @param rowAlive  shared liveness flag; when set to {@code false} this
     *                  local object becomes invalidated
     */
    public SimpleProfileLocalObject(Profile profile, String tableName, AtomicBoolean rowAlive) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (rowAlive == null) {
            throw new IllegalArgumentException("rowAlive is required");
        }
        this.profile = profile;
        this.tableName = tableName;
        this.rowAlive = rowAlive;
    }

    /**
     * Convenience constructor for contexts where no external liveness flag
     * is available (e.g. unit tests of ProfileLocalObject in isolation).
     * Creates a private liveness flag that starts as {@code true}.
     */
    public SimpleProfileLocalObject(Profile profile, String tableName) {
        this(profile, tableName, new AtomicBoolean(true));
    }

    /**
     * Explicitly invalidate this local object. Provided for tests and for
     * cases where the caller directly controls liveness rather than sharing
     * the table's flag.
     */
    public void invalidate() {
        rowAlive.set(false);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isInvalidated() {
        return !rowAlive.get();
    }

    /** {@inheritDoc} */
    @Override
    public Profile getProfile() {
        requireNotInvalidated();
        return profile;
    }

    /** {@inheritDoc} */
    @Override
    public ProfileID getProfileID() {
        requireNotInvalidated();
        ProfileID id = profile.getProfileID();
        if (id != null) {
            return id;
        }
        return new ProfileID(tableName, "?");
    }

    /** {@inheritDoc} */
    @Override
    public String getProfileTableName() {
        requireNotInvalidated();
        ProfileID id = profile.getProfileID();
        return id != null ? id.getProfileTableName() : tableName;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    // -----------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------

    private void requireNotInvalidated() {
        if (!rowAlive.get()) {
            ProfileID id = profile.getProfileID();
            ProfileID known = id != null ? id : new ProfileID(tableName, "?");
            throw new ProfileNotFoundException(
                    "Profile row has been removed; this ProfileLocalObject is stale. "
                    + "Obtain a fresh reference via ProfileFacility.getProfile(). [Contract C8]",
                    known);
        }
    }
}
