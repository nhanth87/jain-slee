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
 * JAIN-SLEE 1.1 §10 — Profile Already Exists Exception.
 * <p>
 * Thrown by {@link ProfileFacility#createProfile(String, String, Class)}
 * when a profile row with the requested primary-key name already exists in
 * the target table.
 *
 * @author Tran Nhan (nhanth87)
 */
public class ProfileAlreadyExistsException extends SLEEException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description of the duplicate profile name
     */
    public ProfileAlreadyExistsException(String message) {
        super(message);
    }

    /**
     * @param message description of the duplicate profile name
     * @param cause   underlying cause (may be {@code null})
     */
    public ProfileAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}