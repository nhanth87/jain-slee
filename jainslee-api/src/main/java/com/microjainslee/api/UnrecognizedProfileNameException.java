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
 * JAIN-SLEE 1.1 §10 — Unrecognized Profile Name Exception.
 * <p>
 * Thrown when a profile lookup references a profile (primary-key) name
 * that the table does not contain.
 *
 * @author Tran Nhan (nhanth87)
 */
public class UnrecognizedProfileNameException extends SLEEException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description of the unrecognized profile name
     */
    public UnrecognizedProfileNameException(String message) {
        super(message);
    }

    /**
     * @param message description of the unrecognized profile name
     * @param cause   underlying cause (may be {@code null})
     */
    public UnrecognizedProfileNameException(String message, Throwable cause) {
        super(message, cause);
    }
}