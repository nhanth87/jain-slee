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
 * JAIN-SLEE 1.1 §5.5 — thrown when a method is invoked on an invalid
 * (removed) SBB local object or when the current transaction has been
 * marked for rollback.
 *
 * @author Tran Nhan (nhanth87)
 */
public class TransactionRolledbackLocalException extends SLEEException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct with a detail message.
     *
     * @param message description of the failure
     */
    public TransactionRolledbackLocalException(String message) {
        super(message);
    }

    /**
     * Construct with a detail message and underlying cause.
     *
     * @param message description of the failure
     * @param cause   underlying cause (may be {@code null})
     */
    public TransactionRolledbackLocalException(String message, Throwable cause) {
        super(message, cause);
    }
}