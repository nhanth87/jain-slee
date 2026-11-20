/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.EventRouter;
import com.microjainslee.core.SleeTimerSchedulerBridge;
import com.microjainslee.core.TimerDeadlineStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gap (4) — D6 on a real multi-member in-process cluster: a deadline imported
 * already in the past fires exactly once cluster-wide (only the lease owner
 * delivers, the non-owner drops+counts), and a cancel replicates so no other
 * node can re-arm the cancelled deadline.
 */
class ClusterTimerDeadlinePastDeadlineTest {

    private static final long TTL_MS = 60_000L;
    private static final String ACTIVITY = "otid:past";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("past deadline at import: fires once cluster-wide, only the lease owner delivers")
    void pastDeadlineFiresOnceClusterWide() throws Exception {
        List<ArmedBridge> armed = new ArrayList<>();
        String name = "timer-past-" + System.nanoTime();
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("past"), name,
                "tp-a", "tp-b")) {
            RaDialogLeaseCaches leaseA = RaDialogLeaseCaches.createUnscheduled(cluster.node(0), "jss7", TTL_MS);
            RaDialogLeaseCaches leaseB = RaDialogLeaseCaches.createUnscheduled(cluster.node(1), "jss7", TTL_MS);
            assertThat(leaseA.tryClaim(ACTIVITY)).isTrue();
            assertThat(leaseB.mayTransmit(ACTIVITY)).isFalse();

            ClusterTimerDeadlineStore storeA = new ClusterTimerDeadlineStore(cluster.node(0));
            ClusterTimerDeadlineStore storeB = new ClusterTimerDeadlineStore(cluster.node(1));

            // Import: a replicated absolute deadline whose time has already passed.
            long importedId = 7_000L;
            long expired = System.currentTimeMillis() - 5_000L;
            storeA.put(new TimerDeadlineStore.TimerDeadline(importedId, ACTIVITY, expired, 42L));
            await(() -> {
                TimerDeadlineStore.TimerDeadline seen = storeB.get(importedId);
                return seen != null && seen.expiresAtEpochMs() == expired;
            }, 20);

            ArmedBridge bridgeA = arm(cluster, 0, storeA, leaseA, ACTIVITY, 0);
            // Burn one timer id on B (before binding the store) so A and B use
            // distinct replicated keys when they arm the imported deadline.
            ArmedBridge bridgeB = arm(cluster, 1, storeB, leaseB, ACTIVITY, 1);
            armed.add(bridgeA);
            armed.add(bridgeB);

            // A past deadline imports as a zero-delay local arm; both nodes arm,
            // only the lease owner delivers the TimerFiredEvent.
            long delayA = Math.max(0L, expired - System.currentTimeMillis());
            long delayB = Math.max(0L, expired - System.currentTimeMillis());
            long idA = bridgeA.bridge.schedule(bridgeA.sbb, delayA);
            long idB = bridgeB.bridge.schedule(bridgeB.sbb, delayB);

            assertThat(bridgeA.router.awaitRouted(5, TimeUnit.SECONDS))
                    .as("lease owner must deliver the past-deadline fire").isTrue();
            assertThat(bridgeA.router.routedCount.get()).isEqualTo(1);
            assertThat(bridgeB.router.awaitRouted(500, TimeUnit.MILLISECONDS))
                    .as("non-owner must NOT deliver").isFalse();
            assertThat(bridgeB.router.routedCount.get()).isZero();
            assertGuardedFires(bridgeB.bridge, 1L);

            await(() -> storeA.get(idA) == null && storeB.get(idB) == null, 10);
        } finally {
            armed.forEach(ArmedBridge::close);
        }
    }

    @Test
    @DisplayName("cancel on one node removes the replicated deadline everywhere")
    void cancelReplicatesAcrossNodes() throws Exception {
        List<ArmedBridge> armed = new ArrayList<>();
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("cancel"),
                "timer-cancel-" + System.nanoTime(), "tc-a", "tc-b")) {
            ClusterTimerDeadlineStore storeA = new ClusterTimerDeadlineStore(cluster.node(0));
            ClusterTimerDeadlineStore storeB = new ClusterTimerDeadlineStore(cluster.node(1));
            ArmedBridge bridgeA = arm(cluster, 0, storeA, null, "otid:cancel", 0);
            armed.add(bridgeA);

            long timerId = bridgeA.bridge.schedule(bridgeA.sbb, 60_000L);
            TimerDeadlineStore.TimerDeadline replicated = storeA.get(timerId);
            assertThat(replicated).isNotNull();
            await(() -> storeB.get(timerId) != null, 20);
            assertThat(storeB.get(timerId).expiresAtEpochMs()).isEqualTo(replicated.expiresAtEpochMs());

            bridgeA.bridge.cancel(timerId);
            await(() -> storeA.get(timerId) == null && storeB.get(timerId) == null, 20);

            // With no replicated deadline, a survivor re-arming from the store
            // finds nothing and no event is delivered.
            assertThat(storeB.get(timerId)).isNull();
            assertThat(bridgeA.router.awaitRouted(500, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(bridgeA.router.routedCount.get()).isZero();
        } finally {
            armed.forEach(ArmedBridge::close);
        }
    }

    /** Arm a bridge on node {@code index}, optionally burning one timer id first. */
    private static ArmedBridge arm(InProcessCluster cluster, int index, TimerDeadlineStore store,
                                   RaDialogLeaseCaches lease, String activity, int burnIds) {
        CountingRouter router = new CountingRouter();
        SleeTimerSchedulerBridge bridge = SleeTimerSchedulerBridge.create(router);
        bridge.setNodeId(cluster.node(index).getNodeId());
        FakeSbb sbb = new FakeSbb();
        if (burnIds > 0) {
            bridge.bindActivityContext(sbb, new NamedAci("burn"));
            for (int i = 0; i < burnIds; i++) {
                long burn = bridge.schedule(sbb, 60_000L);
                bridge.cancel(burn);
            }
        }
        bridge.bindActivityContext(sbb, new NamedAci(activity));
        bridge.setDeadlineStore(store);
        if (lease != null) {
            bridge.setTransmitGuard(lease::mayTransmit);
        }
        return new ArmedBridge(router, bridge, sbb);
    }

    private static void assertGuardedFires(SleeTimerSchedulerBridge bridge, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bridge.guardedFires() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("guardedFires did not reach " + expected);
            }
            Thread.sleep(5L);
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + seconds + "s");
            }
            Thread.sleep(100);
        }
    }

    private static final class ArmedBridge {
        final CountingRouter router;
        final SleeTimerSchedulerBridge bridge;
        final FakeSbb sbb;

        ArmedBridge(CountingRouter router, SleeTimerSchedulerBridge bridge, FakeSbb sbb) {
            this.router = router;
            this.bridge = bridge;
            this.sbb = sbb;
        }

        void close() {
            bridge.shutdown();
            router.shutdown();
        }
    }

    private static final class CountingRouter extends EventRouter {
        final AtomicInteger routedCount = new AtomicInteger();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        CountingRouter() {
            super(16, false);
        }

        boolean awaitRouted(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        @Override
        public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
            routedCount.incrementAndGet();
            latch.countDown();
        }
    }

    private static final class NamedAci implements ActivityContextInterface {
        private final String name;

        NamedAci(String name) {
            this.name = name;
        }

        @Override
        public String getActivityContextName() {
            return name;
        }

        @Override
        public void attach(SbbLocalObject sbbLocalObject) {
        }

        @Override
        public void detach(SbbLocalObject sbbLocalObject) {
        }
    }

    private static final class FakeSbb implements SbbLocalObject {
        private final com.microjainslee.api.SbbID id =
                new com.microjainslee.api.SbbID("cluster-timer-past-test-sbb");

        @Override
        public com.microjainslee.api.Sbb getSbb() {
            return new com.microjainslee.api.Sbb() {
            };
        }

        @Override
        public com.microjainslee.api.SbbID getSbbID() {
            return id;
        }

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public void setPriority(int priority) {
        }

        @Override
        public void remove() {
        }

        @Override
        public boolean isRemoved() {
            return false;
        }
    }
}
