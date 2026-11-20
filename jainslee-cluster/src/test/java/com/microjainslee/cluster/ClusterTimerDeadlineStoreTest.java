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

import com.microjainslee.core.TimerDeadlineStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0007 D6 — absolute timer deadlines survive the death of the arming node.
 */
class ClusterTimerDeadlineStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("deadline written on node A is readable on node B after A is killed")
    void deadlineSurvivesOwnerDeath() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("t1"),
                "timer-dl-" + System.nanoTime(), "tm-a", "tm-b", "tm-w")) {
            ClusterTimerDeadlineStore a = new ClusterTimerDeadlineStore(cluster.node(0));
            ClusterTimerDeadlineStore b = new ClusterTimerDeadlineStore(cluster.node(1));

            long expires = System.currentTimeMillis() + 60_000;
            a.put(new TimerDeadlineStore.TimerDeadline(7L, "otid:42", expires, 3L));

            await(() -> {
                TimerDeadlineStore.TimerDeadline seen = b.get(7L);
                return seen != null && seen.expiresAtEpochMs() == expires;
            }, 20);
            TimerDeadlineStore.TimerDeadline seen = b.get(7L);
            assertThat(seen.activityContextName()).isEqualTo("otid:42");

            cluster.kill(0);
            await(() -> !cluster.node(1).isNodePresent("tm-a"), 20);

            // The survivor still sees the absolute deadline and can re-arm locally.
            TimerDeadlineStore.TimerDeadline afterKill = b.get(7L);
            assertThat(afterKill).isNotNull();
            assertThat(afterKill.expiresAtEpochMs()).isEqualTo(expires);
        }
    }

    @Test
    @DisplayName("remove on one node is visible on the other")
    void removeReplicates() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("t2"),
                "timer-rm-" + System.nanoTime(), "tr-a", "tr-b")) {
            ClusterTimerDeadlineStore a = new ClusterTimerDeadlineStore(cluster.node(0));
            ClusterTimerDeadlineStore b = new ClusterTimerDeadlineStore(cluster.node(1));

            a.put(new TimerDeadlineStore.TimerDeadline(9L, "gmlc-1", System.currentTimeMillis() + 1000, 1L));
            await(() -> b.get(9L) != null, 20);
            b.remove(9L);
            await(() -> a.get(9L) == null, 20);
            assertThat(a.get(9L)).isNull();
        }
    }

    private static void await(BooleanSupplier condition, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeoutSeconds + "s");
            }
            Thread.sleep(100);
        }
    }
}
