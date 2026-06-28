/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.tx;

/**
 * Raised when the Narayana-backed transaction manager cannot complete a
 * begin/commit/rollback cycle — usually because the underlying
 * {@link jakarta.transaction.TransactionManager} blew up with a
 * {@link jakarta.transaction.SystemException} or {@link jakarta.transaction.RollbackException}.
 *
 * <p>This is a {@link RuntimeException} so the SLEE event dispatcher can let it
 * propagate up the existing exception handling pipeline without modifying the
 * checked-exception surface of {@code SbbLocalObject.onEvent(...)}.
 */
public final class JtaTransactionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JtaTransactionException(String message) {
        super(message);
    }

    public JtaTransactionException(String message, Throwable cause) {
        super(message, cause);
    }

    public JtaTransactionException(Throwable cause) {
        super(cause);
    }
}
