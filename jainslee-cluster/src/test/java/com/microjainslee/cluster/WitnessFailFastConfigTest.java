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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.infinispan.configuration.cache.CacheMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gap (2) — witness fail-fast config: a null manager is refused, a blank RA
 * list votes on nothing (no false majority), and every named RA gets a fenced
 * lease cache. A two-member view with no witness fails closed on partition.
 */
class WitnessFailFastConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ClusterWitness.start refuses a null ClusterManager")
    void nullManagerRefused() {
        assertThatThrownBy(() -> ClusterWitness.start(null, List.of("ra-jss7")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clusterManager");
    }

    @Test
    @DisplayName("blank RA names vote on nothing (no false majority)")
    void blankRaNamesVoteOnNothing() throws Exception {
        String name = "witness-blank-" + System.nanoTime();
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("blank"), name,
                "wa", "wb", "ww")) {
            ClusterWitness witness = ClusterWitness.start(cluster.node(2),
                    java.util.Arrays.asList(" ", "", null));
            try {
                assertThat(witness.votedCaches()).isZero();
                assertThat(witness.clusterManager().getNodeId()).isEqualTo("ww");
            } finally {
                witness.close();
            }
        }
    }

    @Test
    @DisplayName("each named RA gets a replicated fenced lease cache")
    void eachNamedRaGetsALeaseCache() throws Exception {
        String name = "witness-ra-" + System.nanoTime();
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("ra"), name,
                "ra", "rb", "rw")) {
            ClusterWitness witness = ClusterWitness.start(cluster.node(2),
                    List.of("ra-jss7", "ra-sms"));
            try {
                assertThat(witness.votedCaches()).isEqualTo(2);
                assertThat(cluster.node(2).getCache(
                        RaDialogLeaseCaches.cacheNameFor("ra-jss7"), CacheMode.REPL_SYNC))
                        .isNotNull();
                assertThat(cluster.node(2).getCache(
                        RaDialogLeaseCaches.cacheNameFor("ra-sms"), CacheMode.REPL_SYNC))
                        .isNotNull();
            } finally {
                witness.close();
            }
        }
    }

    @Test
    @DisplayName("two members without a witness fence both sides on partition")
    void twoMembersWithoutWitnessFenceBothSides() throws Exception {
        String name = "witness-2n-" + System.nanoTime();
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("now"),
                name, "na", "nb")) {
            RaDialogLeaseCaches a = RaDialogLeaseCaches.createUnscheduled(cluster.node(0), "jss7", 60_000L);
            RaDialogLeaseCaches b = RaDialogLeaseCaches.createUnscheduled(cluster.node(1), "jss7", 60_000L);
            assertThat(a.tryClaim("corr-now")).isTrue();
            assertThat(a.mayTransmit("corr-now")).isTrue();

            cluster.isolate(0);
            await(() -> cluster.viewSize(0) == 1 && cluster.viewSize(1) == 1, 30);

            await(() -> !a.mayTransmit("corr-now"), 20);
            await(() -> !b.takeOver("corr-now") && b.takeoversRefused() > 0, 20);
            assertThat(a.fenceBlocked()).isPositive();
            assertThat(b.mayTransmit("corr-now")).isFalse();
            cluster.heal(0);
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + seconds + "s");
            }
            Thread.sleep(150);
        }
    }
}
