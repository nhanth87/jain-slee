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
 * Public SPI for the SLEE transaction boundary.
 *
 * <p>The contract is intentionally minimal so it can be implemented by:
 * <ul>
 *   <li>{@link JtaTransactionManager} — Narayana-backed production manager
 *       used when {@code MicroSleeConfiguration.txEnabled = true}.</li>
 *   <li>{@link NoOpTransactionManager} — fast no-op fallback used by the
 *       default R&D configuration. The kernel then falls back to the
 *       logical undo stack in
 *       {@code com.microjainslee.core.SbbTransactionContext}.</li>
 * </ul>
 *
 * <p>Status values returned by {@link #currentStatus()} mirror
 * {@link jakarta.transaction.Status} but are kept as primitive ints here so
 * the {@code jainslee-tx} module does not force a {@code jakarta.transaction}
 * type into the kernel surface.
 */
public interface TransactionContext {

    /**
     * Wrap {@code task} in a transaction boundary: begin on entry,
     * commit on successful return, rollback on any exception (checked or
     * unchecked).
     *
     * <p>If the task throws, the exception is re-thrown to the caller after
     * rollback completes. Implementations should NOT swallow exceptions.
     *
     * @throws JtaTransactionException if the underlying transaction manager
     *         cannot complete the boundary (commit/rollback itself failed)
     */
    void executeInTransaction(Runnable task);

    /**
     * @return the current transaction status code. Values mirror
     *         {@code jakarta.transaction.Status} (0 active, 1 marked
     *         rollback, 6 no transaction, ...).
     */
    int currentStatus();
}
