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
 * Pending-issues #3 — app correlation id → OTID survives the death of the
 * exporting node, so a survivor publishes on the app id for multi-step MAP
 * flows (ATI / sendRoamingNumber, USSD menus).
 */
class MapAppCorrelationCachesTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("correlation registered on A resolves on B after A is killed")
    void correlationSurvivesOwnerDeath() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("c1"),
                "app-corr-" + System.nanoTime(), "mc-a", "mc-b", "mc-w")) {
            MapAppCorrelationCaches a = new MapAppCorrelationCaches(cluster.node(0));
            MapAppCorrelationCaches b = new MapAppCorrelationCaches(cluster.node(1));

            a.register("gmlc-uuid-1", 42L);
            await(() -> "42".equals(b.resolve("gmlc-uuid-1")), 20);
            assertThat(b.resolve("gmlc-uuid-1")).isEqualTo("42");

            cluster.kill(0);
            await(() -> !cluster.node(1).isNodePresent("mc-a"), 20);

            assertThat(b.resolve("gmlc-uuid-1")).isEqualTo("42");
        }
    }

    @Test
    @DisplayName("null/blank ids are never stored; remove drops the mapping")
    void nullNeverStoredAndRemoveDrops() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tempDir.resolve("c2"),
                "app-corr-rm-" + System.nanoTime(), "mr-a", "mr-b")) {
            MapAppCorrelationCaches a = new MapAppCorrelationCaches(cluster.node(0));
            MapAppCorrelationCaches b = new MapAppCorrelationCaches(cluster.node(1));

            a.register(null, 1L);
            a.register("  ", 1L);
            a.register("gmlc-x", 0L);
            assertThat(a.resolve(null)).isNull();
            assertThat(a.resolve("gmlc-x")).isNull();

            a.register("ussd-menu-7", 77L);
            await(() -> "77".equals(b.resolve("ussd-menu-7")), 20);
            b.remove("ussd-menu-7", 77L);
            await(() -> a.resolve("ussd-menu-7") == null, 20);
            assertThat(a.resolve("ussd-menu-7")).isNull();
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
