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

import java.io.Serializable;
import java.util.Objects;

/**
 * JAIN-SLEE 1.1 §10.3 — Profile Identifier.
 * <p>
 * Immutable composite identifier that uniquely addresses a profile row across
 * the SLEE. It pairs the profile table's logical name with the row's profile
 * name (primary key). Used everywhere a profile row needs to be referenced:
 * {@link ProfileFacility#getProfile(ProfileID)}, {@link ProfileLocalObject#getProfileID()},
 * etc.
 *
 * <p>Two {@code ProfileID} instances are equal when both
 * {@link #getProfileTableName()} and {@link #getProfileName()} match.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileID implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String profileTableName;
    private final String profileName;

    /**
     * Construct a {@code ProfileID}.
     *
     * @param profileTableName name of the profile table; must not be {@code null}
     * @param profileName      profile (primary-key) name within the table; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ProfileID(String profileTableName, String profileName) {
        Objects.requireNonNull(profileTableName, "profileTableName");
        Objects.requireNonNull(profileName, "profileName");
        this.profileTableName = profileTableName;
        this.profileName = profileName;
    }

    /** @return the profile table name (never {@code null}). */
    public String getProfileTableName() {
        return profileTableName;
    }

    /** @return the profile (primary-key) name (never {@code null}). */
    public String getProfileName() {
        return profileName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProfileID)) {
            return false;
        }
        ProfileID other = (ProfileID) o;
        return profileTableName.equals(other.profileTableName)
                && profileName.equals(other.profileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileTableName, profileName);
    }

    /**
     * Canonical string form: {@code "tableName/name"}. Round-trips through
     * log files and error messages but is not parsed back — use
     * {@link #ProfileID(String, String)} for that.
     */
    @Override
    public String toString() {
        return profileTableName + "/" + profileName;
    }
}