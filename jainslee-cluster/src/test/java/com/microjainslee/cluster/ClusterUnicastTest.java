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

import java.io.Serializable;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0007 D11 — real two-member delivery, not a mock. */
class ClusterUnicastTest {

    @TempDir
    Path tempDir;

    private InProcessCluster cluster;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new InProcessCluster(tempDir.resolve("ping"), "unicast-" + System.nanoTime(),
                "uc-a", "uc-b");
    }

    @AfterEach
    void tearDown() {
        cluster.close();
    }

    @Test
    @DisplayName("payload reaches only the named node's handler")
    void deliversToTargetOnly() throws Exception {
        ClusterUnicast a = new ClusterUnicast(cluster.node(0));
        ClusterUnicast b = new ClusterUnicast(cluster.node(1));
        CopyOnWriteArrayList<Serializable> onA = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Serializable> onB = new CopyOnWriteArrayList<>();
        a.register("t", onA::add);
        b.register("t", onB::add);
        try {
            boolean delivered = a.send("uc-b", "t", "hello").get(10, TimeUnit.SECONDS);

            assertThat(delivered).isTrue();
            assertThat(onB).containsExactly("hello");
            assertThat(onA).isEmpty();
        } finally {
            a.unregister("t");
            b.unregister("t");
        }
    }

    @Test
    @DisplayName("unknown node → false, nothing sent")
    void unknownNodeIsNotDelivered() throws Exception {
        ClusterUnicast a = new ClusterUnicast(cluster.node(0));
        assertThat(a.send("uc-zzz", "t", "x").get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    @DisplayName("node id prefix does not match another member (ss7-1 vs ss7-10)")
    void prefixIsNotAMatch() throws Exception {
        ClusterUnicast a = new ClusterUnicast(cluster.node(0));
        // "uc-" is a prefix of both members; it must match neither.
        assertThat(a.send("uc-", "t", "x").get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    @DisplayName("isNodePresent is an exact match on the member name")
    void nodePresenceIsExact() {
        ClusterManager a = cluster.node(0);
        assertThat(a.isNodePresent("uc-b")).isTrue();
        assertThat(a.isNodePresent("uc-")).as("prefix of a member").isFalse();
        assertThat(a.isNodePresent("b")).as("suffix of a member").isFalse();
        assertThat(a.isNodePresent("uc-bb")).isFalse();
    }

    @Test
    @DisplayName("target without a handler → false")
    void noHandlerIsFalse() throws Exception {
        ClusterUnicast a = new ClusterUnicast(cluster.node(0));
        assertThat(a.send("uc-b", "nobody-listens", "x").get(10, TimeUnit.SECONDS)).isFalse();
    }
}
