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
 * Fallback {@link TransactionContext} used when {@code MicroSleeConfiguration.txEnabled}
 * is {@code false} (the R&D default).
 *
 * <p>Behaves as if there is no enclosing transaction: {@link #executeInTransaction(Runnable)}
 * simply runs the task. {@link #currentStatus()} returns
 * {@code jakarta.transaction.Status.STATUS_NO_TRANSACTION} (= 6).
 *
 * <p>The kernel still applies its logical undo stack via
 * {@code com.microjainslee.core.SbbTransactionContext}, so attach / detach /
 * timer-bind side effects are still reversible on exception even without
 * a real JTA transaction.
 */
public final class NoOpTransactionManager implements TransactionContext {

    /** Mirrors {@code jakarta.transaction.Status.STATUS_NO_TRANSACTION}. */
    public static final int STATUS_NO_TRANSACTION = 6;

    @Override
    public void executeInTransaction(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        task.run();
    }

    @Override
    public int currentStatus() {
        return STATUS_NO_TRANSACTION;
    }
}
