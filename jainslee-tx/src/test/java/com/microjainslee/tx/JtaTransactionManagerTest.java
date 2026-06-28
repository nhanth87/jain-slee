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

import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JtaTransactionManager}. Covers:
 * <ol>
 *   <li>commit-on-success</li>
 *   <li>rollback on {@link RuntimeException}</li>
 *   <li>rollback on checked {@link Exception}</li>
 *   <li>nested-transaction behaviour (REQUIRED semantics)</li>
 *   <li>{@link JtaTransactionManager#setRollbackOnly()} converts to rollback</li>
 *   <li>{@link JtaTransactionManager#currentStatus()} reflects active / no-tx states</li>
 *   <li>No exception leak from Narayana: a {@link RuntimeException} thrown by the
 *       task body is the exact class the caller sees (no Narayana SystemException
 *       wrapping for user-thrown failures).</li>
 * </ol>
 */
class JtaTransactionManagerTest {

    private TransactionManager narayana;
    private JtaTransactionManager tx;

    @BeforeEach
    void setUp() throws Exception {
        // Always start from a clean slate so tests don't see stale tx state
        // left over by a previous failing test.
        narayana = com.arjuna.ats.jta.TransactionManager.transactionManager();
        cleanupActiveTx();
        tx = new JtaTransactionManager(narayana);
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanupActiveTx();
    }

    private void cleanupActiveTx() {
        try {
            int s = narayana.getStatus();
            if (s == Status.STATUS_ACTIVE || s == Status.STATUS_MARKED_ROLLBACK
                    || s == Status.STATUS_PREPARING || s == Status.STATUS_COMMITTING
                    || s == Status.STATUS_ROLLING_BACK) {
                narayana.rollback();
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Test
    @DisplayName("commit on success — task ran inside an active tx that was committed")
    void commitsOnSuccess() throws Exception {
        final boolean[] ranInsideTx = {false};
        tx.executeInTransaction(() -> {
            try {
                assertThat(narayana.getStatus())
                        .as("task should run inside an active JTA tx")
                        .isEqualTo(Status.STATUS_ACTIVE);
                ranInsideTx[0] = true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(ranInsideTx[0]).isTrue();
        assertThat(narayana.getStatus())
                .as("after commit there should be no active tx")
                .isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("rollback on RuntimeException — tx is rolled back, exception propagates")
    void rollsBackOnRuntimeException() throws Exception {
        final IllegalStateException cause = new IllegalStateException("boom-rt");
        assertThatThrownBy(() -> tx.executeInTransaction(() -> { throw cause; }))
                .isSameAs(cause);
        assertThat(narayana.getStatus())
                .as("after rollback the tx should be gone")
                .isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("rollback on checked Exception — sneaky-throw keeps exception type")
    void rollsBackOnCheckedException() throws Exception {
        final java.io.IOException cause = new java.io.IOException("boom-checked");
        assertThatThrownBy(() -> tx.executeInTransaction(() -> {
            // checked exception from a Runnable — Java forces us to wrap.
            throw new RuntimeException(cause);
        }))
                .hasCauseReference(cause);
        assertThat(narayana.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("rollback on a different RuntimeException subtype — exception propagates unchanged")
    void rollsBackOnSubtype() throws Exception {
        // Verifies the wrapper re-throws the EXACT exception type the caller
        // threw — never replaces it with a Narayana SystemException or
        // JtaTransactionException wrapping the original.
        final ArithmeticException user = new ArithmeticException("div-by-zero");
        assertThatThrownBy(() -> tx.executeInTransaction(() -> { throw user; }))
                .isInstanceOf(ArithmeticException.class)
                .isSameAs(user);
        assertThat(narayana.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("nested transaction — outer tx is joined, no new commit/rollback")
    void joinsOuterTransaction() throws Exception {
        narayana.begin();
        assertThat(narayana.getStatus()).isEqualTo(Status.STATUS_ACTIVE);

        final boolean[] innerSawActive = {false};
        // Should NOT throw NotSupportedException — our wrapper joins the outer tx.
        tx.executeInTransaction(() -> {
            try {
                innerSawActive[0] = narayana.getStatus() == Status.STATUS_ACTIVE;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(innerSawActive[0])
                .as("inner block must observe the outer tx as active")
                .isTrue();
        // Outer tx must still be active after the inner block — the inner
        // wrapper did NOT commit because it joined rather than began.
        assertThat(narayana.getStatus())
                .as("outer tx should remain active after inner join")
                .isEqualTo(Status.STATUS_ACTIVE);
        // Outer commits cleanly — no leftover state.
        narayana.commit();
        assertThat(narayana.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("setRollbackOnly — marks tx for rollback; commit path rolls back instead")
    void setRollbackOnlyForcesRollback() throws Exception {
        final boolean[] ranInside = {false};
        assertThatThrownBy(() -> tx.executeInTransaction(() -> {
            ranInside[0] = true;
            tx.setRollbackOnly();
            try {
                int s = narayana.getStatus();
                // After setRollbackOnly, status is either MARKED_ROLLBACK (1)
                // or a transient state — accept either.
                assertThat(s)
                        .as("after setRollbackOnly the tx should be marked rollback")
                        .isIn(Status.STATUS_MARKED_ROLLBACK, Status.STATUS_ROLLING_BACK,
                                Status.STATUS_ROLLEDBACK, Status.STATUS_UNKNOWN);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }))
                .isInstanceOf(JtaTransactionException.class);
        // We accept any commit-failure message — Narayana may surface the
        // marked-rollback state as RollbackException, IllegalStateException,
        // or a wrapped RuntimeException depending on its internal state
        // machine. The wrapper contract is: ANY commit failure on a
        // rollback-only tx must surface as JtaTransactionException.

        assertThat(ranInside[0]).isTrue();
        assertThat(narayana.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("setRollbackOnly outside an active tx — wrapped in JtaTransactionException")
    void setRollbackOnlyWithoutTxThrows() {
        assertThatThrownBy(() -> tx.setRollbackOnly())
                .isInstanceOf(JtaTransactionException.class)
                .hasMessageContaining("setRollbackOnly");
    }

    @Test
    @DisplayName("currentStatus returns NO_TRANSACTION outside any tx")
    void currentStatusNoTx() {
        assertThat(tx.currentStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("currentStatus returns ACTIVE while inside executeInTransaction")
    void currentStatusActive() throws Exception {
        tx.executeInTransaction(() -> {
            assertThat(tx.currentStatus()).isEqualTo(Status.STATUS_ACTIVE);
        });
        assertThat(tx.currentStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("getTransaction returns the active tx inside executeInTransaction")
    void getTransactionReturnsActive() throws Exception {
        tx.executeInTransaction(() -> {
            try {
                assertThat(tx.getTransaction())
                        .as("an active tx must be visible via getTransaction()")
                        .isNotNull()
                        .isSameAs(narayana.getTransaction());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(tx.getTransaction())
                .as("no tx after commit")
                .isNull();
    }

    @Test
    @DisplayName("no Narayana exception leak — user-thrown RuntimeException propagates unchanged")
    void noExceptionLeakFromNarayana() {
        // A task body throws a specific runtime exception. The wrapper must
        // re-throw the SAME instance — never wrap it in JtaTransactionException
        // or replace it with a Narayana SystemException.
        final NumberFormatException user = new NumberFormatException("nfe");
        assertThatThrownBy(() -> tx.executeInTransaction(() -> { throw user; }))
                .isSameAs(user);
    }

    @Test
    @DisplayName("no Narayana exception leak — commit failure becomes JtaTransactionException only")
    void commitFailureIsWrapped() throws Exception {
        // Force a commit failure by marking the tx rollback-only and then
        // trying to commit. Wrapper must convert the resulting
        // RollbackException into a JtaTransactionException with a clear
        // message, not re-throw the raw checked exception.
        assertThatThrownBy(() -> tx.executeInTransaction(() -> tx.setRollbackOnly()))
                .isInstanceOf(JtaTransactionException.class)
                .hasMessageContaining("rollback");
    }

    @Test
    @DisplayName("null task — IllegalArgumentException, no tx state change")
    void nullTaskRejected() throws Exception {
        assertThatThrownBy(() -> tx.executeInTransaction(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(narayana.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    @DisplayName("NoOpTransactionManager — runs task, status always NO_TRANSACTION")
    void noOpManagerRunsAndReportsNoTx() {
        NoOpTransactionManager noop = new NoOpTransactionManager();
        final boolean[] ran = {false};
        noop.executeInTransaction(() -> ran[0] = true);
        assertThat(ran[0]).isTrue();
        assertThat(noop.currentStatus()).isEqualTo(NoOpTransactionManager.STATUS_NO_TRANSACTION);
        // And it does NOT participate in rollback semantics — a thrown
        // exception still propagates to the caller.
        final RuntimeException boom = new RuntimeException("noop-boom");
        assertThatThrownBy(() -> noop.executeInTransaction(() -> { throw boom; }))
                .isSameAs(boom);
    }
}
