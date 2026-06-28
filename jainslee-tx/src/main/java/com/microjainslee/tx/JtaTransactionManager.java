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

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Narayana JTA 7.0-backed {@link TransactionContext}.
 *
 * <p>Wraps every SBB event handler invocation in a JTA transaction so
 * profile / timer / activity side effects can participate in two-phase
 * commit when wired to XA resources in later phases (P2 / P5).
 *
 * <h2>Virtual-Thread pinning</h2>
 * Narayana 7.0 uses {@code synchronized} blocks internally — running
 * {@link #executeInTransaction(Runnable)} on a virtual thread may pin the
 * carrier. Production P1.2 mitigates this two ways:
 * <ol>
 *   <li>The EventRouter always calls us from a platform thread (the
 *       Disruptor handler or the SBB entity thread).</li>
 *   <li>{@code -Djdk.tracePinnedThreads=full} is recommended in dev/CI;
 *       if pinning shows up we replace {@code synchronized} with a
 *       {@link java.util.concurrent.locks.ReentrantLock} here.</li>
 * </ol>
 *
 * <h2>Nested transactions</h2>
 * {@link TransactionManager#begin()} throws
 * {@link NotSupportedException} if a transaction is already active on the
 * current thread. We treat that case as "join the existing transaction":
 * run the task inside the outer TX, leave commit/rollback to the outer
 * boundary. This matches the JTA spec's "flat transaction" model and
 * matches what {@code @Transactional(REQUIRED)} does in Spring.
 */
public final class JtaTransactionManager implements TransactionContext {

    private static final Logger LOG = LogManager.getLogger(JtaTransactionManager.class);

    private final TransactionManager tm;

    /**
     * Resolve the singleton Narayana {@link TransactionManager}. Resolution is
     * delegated to {@code com.arjuna.ats.jta.TransactionManager.transactionManager()}
     * which lazily initialises the Narayana runtime on first use. We capture
     * the reference once so subsequent calls are just method invocations on
     * the singleton — no extra lookup cost per event.
     */
    public JtaTransactionManager() {
        this(com.arjuna.ats.jta.TransactionManager.transactionManager());
    }

    /** Constructor for tests / advanced wiring that want a custom TM instance. */
    public JtaTransactionManager(TransactionManager tm) {
        if (tm == null) {
            throw new IllegalArgumentException("TransactionManager is required");
        }
        this.tm = tm;
    }

    @Override
    public void executeInTransaction(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        boolean beganHere = false;
        try {
            int before = safeStatus();
            if (before == Status.STATUS_NO_TRANSACTION) {
                tm.begin();
                beganHere = true;
            } else {
                LOG.debug("Joining existing JTA transaction (status={})", before);
            }
        } catch (NotSupportedException nse) {
            throw new JtaTransactionException(
                    "JTA begin failed: transaction already active", nse);
        } catch (SystemException se) {
            throw new JtaTransactionException(
                    "JTA begin failed: underlying system error", se);
        } catch (RuntimeException re) {
            throw new JtaTransactionException("JTA begin failed", re);
        }

        try {
            task.run();
        } catch (Throwable taskFailure) {
            // User code threw — drive rollback for the tx we started and
            // re-throw so the EventRouter can route to its error policy.
            if (beganHere) {
                try {
                    rollbackSilently();
                } catch (RuntimeException rollbackFailure) {
                    // attach the rollback failure to the original task failure
                    taskFailure.addSuppressed(rollbackFailure);
                }
            }
            rethrowUnchecked(taskFailure);
            // Unreachable — rethrowUnchecked always throws — but the compiler
            // insists on a return path.
            throw new AssertionError("unreachable");
        }

        // Success path: commit only if we initiated this tx. The tx might
        // have been marked rollback-only inside the task body — honor that
        // and convert to rollback.
        if (!beganHere) {
            return;
        }
        // Probe for marked-rollback BEFORE entering the commit try-block so
        // the explicit JtaTransactionException throw isn't accidentally
        // caught by the generic RuntimeException catch below.
        boolean markedRollbackOnly;
        try {
            markedRollbackOnly = safeStatus() == Status.STATUS_MARKED_ROLLBACK;
        } catch (RuntimeException probeFailure) {
            // If we cannot even probe the status, fall through and let
            // commit() surface the real error.
            markedRollbackOnly = false;
        }
        if (markedRollbackOnly) {
            rollbackSilently();
            throw new JtaTransactionException(
                    "JTA transaction was marked rollback-only; rolled back");
        }
        try {
            tm.commit();
        } catch (RollbackException re) {
            throw new JtaTransactionException("JTA commit rolled back", re);
        } catch (HeuristicMixedException hme) {
            throw new JtaTransactionException(
                    "JTA commit heuristic-mixed; outcome is indeterminate", hme);
        } catch (HeuristicRollbackException hre) {
            throw new JtaTransactionException(
                    "JTA commit heuristic-rollback; outcome is indeterminate", hre);
        } catch (SecurityException se) {
            throw new JtaTransactionException(
                    "JTA commit denied by security policy", se);
        } catch (IllegalStateException ise) {
            throw new JtaTransactionException(
                    "JTA commit rejected: illegal state", ise);
        } catch (SystemException se) {
            throw new JtaTransactionException(
                    "JTA commit failed: underlying system error", se);
        } catch (RuntimeException re) {
            throw new JtaTransactionException("JTA commit failed", re);
        }
    }

    @Override
    public int currentStatus() {
        return safeStatus();
    }

    /**
     * Mark the current transaction rollback-only. Useful for SBBs that detect
     * an unrecoverable state and want to bail without throwing.
     */
    public void setRollbackOnly() {
        try {
            tm.setRollbackOnly();
        } catch (IllegalStateException ise) {
            throw new JtaTransactionException(
                    "setRollbackOnly rejected: no active transaction", ise);
        } catch (SystemException se) {
            throw new JtaTransactionException(
                    "setRollbackOnly failed: underlying system error", se);
        }
    }

    /**
     * @return the current JTA {@link Transaction}, or {@code null} if there is
     *         none active on this thread.
     */
    public Transaction getTransaction() {
        try {
            return tm.getTransaction();
        } catch (SystemException se) {
            throw new JtaTransactionException(
                    "getTransaction failed: underlying system error", se);
        }
    }

    private int safeStatus() {
        try {
            return tm.getStatus();
        } catch (SystemException se) {
            // Surface as the equivalent of "unknown" — the JTA spec only
            // defines 0..9, so 5 (UNKNOWN) is the closest legal match and
            // signals the dispatcher that the tx state is indeterminate.
            LOG.warn("JTA getStatus failed, returning STATUS_UNKNOWN", se);
            return Status.STATUS_UNKNOWN;
        }
    }

    private void rollbackSilently() {
        try {
            tm.rollback();
        } catch (IllegalStateException ise) {
            throw new JtaTransactionException(
                    "rollback rejected: illegal state", ise);
        } catch (SecurityException se) {
            throw new JtaTransactionException(
                    "rollback denied by security policy", se);
        } catch (SystemException se) {
            throw new JtaTransactionException(
                    "rollback failed: underlying system error", se);
        }
    }

    /**
     * Re-throw a {@link Throwable} preserving its checked / unchecked nature.
     * Uses the standard "sneaky throw" trick so the public API can stay
     * {@code void} with no checked-exception declaration.
     */
    private static void rethrowUnchecked(Throwable t) {
        JtaTransactionManager.<RuntimeException>sneakyThrow(t);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
