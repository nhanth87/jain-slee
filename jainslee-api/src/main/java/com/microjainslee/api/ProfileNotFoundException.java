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
 * JAIN-SLEE 1.1 §10 — Profile Not Found Exception (Phase 1, Contract C8).
 * <p>
 * Thrown by {@link ProfileLocalObject} accessors when the underlying profile
 * row has been removed while a caller still holds a stale local object, and
 * by atomic operations on {@link ProfileFacility} when the target row does not
 * exist. This gives callers a typed, unambiguous signal that the row is gone —
 * not a silent {@code null} return.
 *
 * <p>Spec note: JAIN-SLEE 1.1 does not define this exact exception class; this
 * is a micro-jainslee extension that aligns with the contract C8 decision
 * recorded in {@code design-ideas/PROFILE-IMPLEMENTATION-PLAN.md}.
 *
 * @author Tran Nhan (nhanth87)
 */
public class ProfileNotFoundException extends SLEEException {

    private static final long serialVersionUID = 1L;

    private final ProfileID profileID;

    /**
     * Construct a {@code ProfileNotFoundException} identifying the missing row.
     *
     * @param profileID the identity of the profile that was not found
     */
    public ProfileNotFoundException(ProfileID profileID) {
        super("Profile not found: " + profileID);
        this.profileID = profileID;
    }

    /**
     * Construct a {@code ProfileNotFoundException} with a custom message.
     *
     * @param message   detail message
     * @param profileID the identity of the profile that was not found
     */
    public ProfileNotFoundException(String message, ProfileID profileID) {
        super(message);
        this.profileID = profileID;
    }

    /**
     * @return the identity of the profile row that was not found,
     *         or {@code null} when the row identity is not known at throw time
     */
    public ProfileID getProfileID() {
        return profileID;
    }
}
