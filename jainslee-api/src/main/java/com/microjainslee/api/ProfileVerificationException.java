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
 * JAIN-SLEE 1.1 §10 — Profile Verification Exception.
 * <p>
 * Typed marker raised when a Profile Specification fails verification at
 * deploy time (e.g. an abstract accessor with no matching column on the
 * backing store, or a required CMP field missing from the generated
 * concrete class). Retained as a typed marker so callers can distinguish
 * deployment-time profile errors from runtime failures.
 *
 * @author Tran Nhan (nhanth87)
 */
public class ProfileVerificationException extends SLEEException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct with a detail message.
     *
     * @param message description of the verification failure
     */
    public ProfileVerificationException(String message) {
        super(message);
    }

    /**
     * Construct with a detail message and underlying cause.
     *
     * @param message description of the verification failure
     * @param cause   underlying cause (may be {@code null})
     */
    public ProfileVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}