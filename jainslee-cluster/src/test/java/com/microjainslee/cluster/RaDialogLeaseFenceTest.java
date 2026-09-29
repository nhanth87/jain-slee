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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0007 D3 / D12 — leases and the transmit fence on <b>real</b> JGroups
 * members, including a partition (scenario C) with the process still alive.
 */
class RaDialogLeaseFenceTest {

    private static final String RA = "jss7";
    private static final long TTL_MS = 60_000L;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("claim before send: the second node's claim of the same activity is refused")
    void secondClaimIsRefused() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("p1"), "lease-claim-" + System.nanoTime(),
                "ls-a", "ls-b")) {
            RaDialogLeaseCaches a = RaDialogLeaseCaches.createUnscheduled(cluster.node(0), RA, TTL_MS);
            RaDialogLeaseCaches b = RaDialogLeaseCaches.createUnscheduled(cluster.node(1), RA, TTL_MS);

            assertThat(a.tryClaim("gmlc-1")).isTrue();
            assertThat(b.tryClaim("gmlc-1")).as("a retry on another node must not send a 2nd BEGIN").isFalse();
            assertThat(a.mayTransmit("gmlc-1")).isTrue();
            assertThat(b.mayTransmit("gmlc-1")).as("non-owner never transmits").isFalse();
            assertThat(b.takeOver("gmlc-1")).as("owner alive → no takeover").isFalse();
        }
    }

    @Test
    @DisplayName("owner crash: survivor takes over with generation + 1")
    void survivorTakesOverAfterCrash() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("p2"), "lease-crash-" + System.nanoTime(),
                "lc-a", "lc-b", "lc-w")) {
            RaDialogLeaseCaches a = RaDialogLeaseCaches.createUnscheduled(cluster.node(0), RA, TTL_MS);
            RaDialogLeaseCaches b = RaDialogLeaseCaches.createUnscheduled(cluster.node(1), RA, TTL_MS);
            RaDialogLeaseCaches.createUnscheduled(cluster.node(2), RA, TTL_MS);     // witness
            assertThat(a.tryClaim("gmlc-2")).isTrue();

            cluster.kill(0);
            await(() -> !cluster.node(1).isNodePresent("lc-a"), 20);

            assertThat(b.takeOver("gmlc-2")).isTrue();
            RaDialogLease lease = b.lookup("gmlc-2");
            assertThat(lease.ownerNodeId()).isEqualTo("lc-b");
            assertThat(lease.generation()).isEqualTo(1L);
            assertThat(b.mayTransmit("gmlc-2")).isTrue();
        }
    }

    @Test
    @DisplayName("scenario C: partitioned owner stops transmitting; majority takes over; merge keeps the newer lease")
    void partitionFencesTheMinority() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("p3"), "lease-part-" + System.nanoTime(),
                "lp-a", "lp-b", "lp-w")) {
            RaDialogLeaseCaches a = RaDialogLeaseCaches.createUnscheduled(cluster.node(0), RA, TTL_MS);
            RaDialogLeaseCaches b = RaDialogLeaseCaches.createUnscheduled(cluster.node(1), RA, TTL_MS);
            ClusterWitness witness = ClusterWitness.start(cluster.node(2), java.util.List.of(RA));
            assertThat(witness.votedCaches()).isEqualTo(1);
            assertThat(a.tryClaim("gmlc-3")).isTrue();
            assertThat(a.mayTransmit("gmlc-3")).isTrue();

            cluster.isolate(0);
            await(() -> cluster.viewSize(1) == 2 && cluster.viewSize(0) == 1, 30);

            // The zombie is alive and still believes it owns the dialog — the fence must stop it.
            await(() -> !a.mayTransmit("gmlc-3"), 20);
            assertThat(a.fenceBlocked()).isPositive();

            // The majority (B + witness) may take it over.
            await(() -> b.takeOver("gmlc-3"), 20);
            assertThat(b.lookup("gmlc-3").generation()).isEqualTo(1L);
            assertThat(b.mayTransmit("gmlc-3")).isTrue();

            cluster.heal(0);
            await(() -> cluster.viewSize(0) == 3 && cluster.viewSize(1) == 3, 60);
            await(() -> {
                RaDialogLease seen = a.lookup("gmlc-3");
                return seen != null && "lp-b".equals(seen.ownerNodeId());
            }, 30);
            assertThat(a.lookup("gmlc-3").generation()).as("merge keeps the higher generation").isEqualTo(1L);
            assertThat(a.mayTransmit("gmlc-3")).as("healed zombie still must not send").isFalse();
        }
    }

    @Test
    @DisplayName("two nodes, no witness: a split leaves NO side able to take over (why D12 needs a 3rd member)")
    void twoNodesWithoutWitnessCannotTakeOver() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("p4"), "lease-2n-" + System.nanoTime(),
                "l2-a", "l2-b")) {
            RaDialogLeaseCaches a = RaDialogLeaseCaches.createUnscheduled(cluster.node(0), RA, TTL_MS);
            RaDialogLeaseCaches b = RaDialogLeaseCaches.createUnscheduled(cluster.node(1), RA, TTL_MS);
            assertThat(a.tryClaim("gmlc-4")).isTrue();

            cluster.isolate(0);
            await(() -> cluster.viewSize(1) == 1 && cluster.viewSize(0) == 1, 30);

            await(() -> !a.mayTransmit("gmlc-4"), 20);
            await(() -> !b.takeOver("gmlc-4") && b.takeoversRefused() > 0, 20);
            assertThat(b.mayTransmit("gmlc-4")).isFalse();
            cluster.heal(0);
        }
    }

    @Test
    @DisplayName("heartbeat renews own leases; orphan GC never assigns ownership")
    void heartbeatAndGarbageCollection() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("p5"), "lease-hb-" + System.nanoTime(),
                "lh-a", "lh-b", "lh-w")) {
            RaDialogLeaseCaches a = RaDialogLeaseCaches.createUnscheduled(cluster.node(0), RA, 1_000L);
            RaDialogLeaseCaches b = RaDialogLeaseCaches.createUnscheduled(cluster.node(1), RA, 1_000L);
            RaDialogLeaseCaches.createUnscheduled(cluster.node(2), RA, 1_000L);
            assertThat(a.tryClaim("gmlc-5")).isTrue();
            long before = a.lookup("gmlc-5").leaseExpiresAtEpochMs();
            Thread.sleep(600);                                  // past half the TTL
            assertThat(a.heartbeat()).isEqualTo(1);
            assertThat(a.lookup("gmlc-5").leaseExpiresAtEpochMs()).isGreaterThan(before);

            // B's GC with A alive: nothing removed, nothing reassigned.
            Thread.sleep(2_200);
            assertThat(b.collectOrphans()).isEmpty();
            assertThat(b.lookup("gmlc-5").ownerNodeId()).isEqualTo("lh-a");

            // A gone and expired for a full TTL: B removes it — and does not take it.
            cluster.kill(0);
            await(() -> !cluster.node(1).isNodePresent("lh-a"), 20);
            await(() -> !b.collectOrphans().isEmpty() || b.lookup("gmlc-5") == null, 10);
            assertThat(b.lookup("gmlc-5")).isNull();
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(condition.getAsBoolean()).as("condition not reached in " + seconds + "s").isTrue();
    }
}
