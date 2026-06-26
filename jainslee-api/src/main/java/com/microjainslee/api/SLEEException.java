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
 * JAIN-SLEE 1.1 §5 — base SLEE exception.
 * <p>
 * Root of the SLEE exception hierarchy. All checked and unchecked SLEE-level
 * failures derive from this type so that callers can uniformly catch container
 * errors.
 *
 * @author Tran Nhan (nhanth87)
 */
public class SLEEException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct an {@code SLEEException} with the given detail message.
     *
     * @param message description of the failure
     */
    public SLEEException(String message) {
        super(message);
    }

    /**
     * Construct an {@code SLEEException} with a detail message and underlying cause.
     *
     * @param message description of the failure
     * @param cause   underlying cause (may be {@code null})
     */
    public SLEEException(String message, Throwable cause) {
        super(message, cause);
    }
}