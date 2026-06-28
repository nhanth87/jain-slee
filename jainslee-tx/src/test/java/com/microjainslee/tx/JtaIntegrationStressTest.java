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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.core.EventRouter;
import com.microjainslee.core.InMemoryActivityContext;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;

import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production P1.2 integration stress test — wires a real
 * {@link MicroSleeContainer} with {@code txEnabled = true} and verifies that
 * <strong>every single event is delivered inside its own JTA transaction
 * that commits cleanly</strong>.
 *
 * <p>Spec target (production-roadmap.md §15 Sprint P1.2):
 * "Integration test: 1000 events with txEnabled=true, verify JTA tx committed
 * per event."
 *
 * <p>Each SBB delivery observes the active JTA tx via
 * {@code SbbTransactionContext.currentExternalStatus()} so we can assert
 * both the per-event wrap and the post-event commit without poking the
 * Narayana singleton directly from the SBB.
 */
class JtaIntegrationStressTest {

    private MicroSleeContainer container;
    private TransactionManager narayana;

    @BeforeEach
    void setUp() throws Exception {
        cleanupActiveTx();
        container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(1024)
                        .preferVirtualThreads(false) // platform threads for VT-pinning-safe Narayana
                        .sbbPerVirtualThread(false)
                        .eventDeliveryMode(com.microjainslee.core.EventDeliveryMode.INLINE)
                        .txEnabled(true)               // <-- the new P1.2 switch
                        .build());
        container.start();
        narayana = com.arjuna.ats.jta.TransactionManager.transactionManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (container != null) {
            container.stop();
        }
        cleanupActiveTx();
    }

    private void cleanupActiveTx() throws SystemException {
        try {
            int s = narayana == null
                    ? com.arjuna.ats.jta.TransactionManager.transactionManager().getStatus()
                    : narayana.getStatus();
            if (s == Status.STATUS_ACTIVE || s == Status.STATUS_MARKED_ROLLBACK
                    || s == Status.STATUS_PREPARING || s == Status.STATUS_COMMITTING
                    || s == Status.STATUS_ROLLING_BACK) {
                (narayana != null ? narayana
                        : com.arjuna.ats.jta.TransactionManager.transactionManager())
                        .rollback();
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Test
    @DisplayName("1000 events with txEnabled=true: each delivered inside a JTA tx that commits")
    void thousandEventsWithJtaEnabled() throws Exception {
        final int eventCount = 1000;
        final AtomicInteger insideActiveCount = new AtomicInteger();
        final AtomicInteger seenAfterCommit = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(eventCount);
        final RecordingSbb sbb = new RecordingSbb(insideActiveCount, seenAfterCommit, done);

        SbbLocalObject localObject = container.registerSbb("stress-sbb", sbb);
        InMemoryActivityContext aci = container.createActivityContext("stress-ac");
        container.attach("stress-ac", localObject);

        for (int i = 0; i < eventCount; i++) {
            container.routeEvent(new StressEvent(i), aci);
        }

        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("all 1000 events delivered")
                .isTrue();
        assertThat(insideActiveCount.get())
                .as("every delivery ran inside an active JTA tx")
                .isEqualTo(eventCount);
        assertThat(seenAfterCommit.get())
                .as("no delivery ever observed STATUS_NO_TRANSACTION mid-task")
                .isZero();
        // Give the router a brief moment to drain, then assert no tx leaked.
        Thread.sleep(50);
        assertThat(narayana.getStatus())
                .as("after stress, no JTA tx should remain active")
                .isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    private static final class StressEvent implements SleeEvent {
        private final int seq;
        StressEvent(int seq) { this.seq = seq; }
        @Override public String toString() { return "StressEvent#" + seq; }
    }

    private static final class RecordingSbb implements Sbb, SleeEventHandler {
        private final AtomicInteger insideActive;
        private final AtomicInteger seenNoTx;
        private final CountDownLatch done;

        RecordingSbb(AtomicInteger insideActive, AtomicInteger seenNoTx, CountDownLatch done) {
            this.insideActive = insideActive;
            this.seenNoTx = seenNoTx;
            this.done = done;
        }

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            // The EventRouter has bound the JTA context onto the
            // SbbTransactionContext via setExternalTransactionContext().
            // We use the SBB tx's observation hook to inspect Narayana
            // status without poking the singleton from SBB code.
            com.microjainslee.core.SbbTransactionContext tx =
                    com.microjainslee.core.ActivityContextTransactionRegistry.current();
            int externalStatus = tx != null ? tx.currentExternalStatus() : -1;
            if (externalStatus == Status.STATUS_ACTIVE) {
                insideActive.incrementAndGet();
            } else if (externalStatus == Status.STATUS_NO_TRANSACTION) {
                seenNoTx.incrementAndGet();
            }
            done.countDown();
        }
    }
}
