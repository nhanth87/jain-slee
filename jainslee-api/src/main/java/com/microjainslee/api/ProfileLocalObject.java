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
 * JAIN-SLEE 1.1 §10.7.6 — Profile Local Object interface.
 * <p>
 * Base interface of every profile's local handle. The SLEE supplies the
 * concrete implementation; the user-defined Profile Specification's local
 * interface extends this type and adds profile-specific business methods.
 *
 * <p>Analog of {@link SbbLocalObject}, but for profiles.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface ProfileLocalObject {

    /**
     * Obtain the {@link Profile} (CMP-state snapshot) currently bound to this
     * profile row. May return {@code null} when the profile has been removed
     * or is otherwise detached.
     *
     * @return the profile object, or {@code null} if no longer attached
     */
    Profile getProfile();

    /**
     * Identifier of the profile row this local object refers to.
     *
     * @return the profile ID (never {@code null})
     */
    ProfileID getProfileID();

    /**
     * Convenience shortcut for {@code getProfileID().getProfileTableName()}.
     *
     * @return the profile table name
     */
    String getProfileTableName();

    /**
     * Whether this profile row is currently in read-only mode.
     * <p>
     * Read-only profiles reject {@link Profile#setCmpField(String, Object)}
     * calls. JAIN SLEE 1.1 §10.10 defines read-only semantics for profiles
     * that are mid-query or otherwise locked for safe publication.
     *
     * @return {@code true} when this profile is read-only
     */
    boolean isReadOnly();
}