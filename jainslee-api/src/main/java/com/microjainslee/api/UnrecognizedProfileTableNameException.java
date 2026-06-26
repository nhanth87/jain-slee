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
 * JAIN-SLEE 1.1 §10 — Unrecognized Profile Table Name Exception.
 * <p>
 * Thrown when an operation references a profile table name that the
 * container does not know about.
 *
 * @author Tran Nhan (nhanth87)
 */
public class UnrecognizedProfileTableNameException extends SLEEException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description of the unrecognized table name
     */
    public UnrecognizedProfileTableNameException(String message) {
        super(message);
    }

    /**
     * @param message description of the unrecognized table name
     * @param cause   underlying cause (may be {@code null})
     */
    public UnrecognizedProfileTableNameException(String message, Throwable cause) {
        super(message, cause);
    }
}