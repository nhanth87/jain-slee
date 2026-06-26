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
 * JAIN-SLEE 1.1 §5.5 — thrown when a mandatory transactional method is
 * invoked without an active transaction context.
 *
 * @author Tran Nhan (nhanth87)
 */
public class TransactionRequiredLocalException extends SLEEException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct with the default message {@code "Transaction required"}.
     */
    public TransactionRequiredLocalException() {
        super("Transaction required");
    }
}