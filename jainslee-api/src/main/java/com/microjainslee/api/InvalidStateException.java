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
 * JAIN-SLEE 1.1 §10 — Invalid State Exception.
 * <p>
 * Typed marker raised when a profile facility operation is invoked while
 * the underlying profile or container is in a state that disallows the
 * requested operation (e.g. a write against a read-only profile, or a
 * lookup against a profile table that is being re-indexed).
 *
 * @author Tran Nhan (nhanth87)
 */
public class InvalidStateException extends SLEEException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct with a detail message.
     *
     * @param message description of the invalid state
     */
    public InvalidStateException(String message) {
        super(message);
    }

    /**
     * Construct with a detail message and underlying cause.
     *
     * @param message description of the invalid state
     * @param cause   underlying cause (may be {@code null})
     */
    public InvalidStateException(String message, Throwable cause) {
        super(message, cause);
    }
}