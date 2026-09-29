/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.jss7.cluster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.microjainslee.cluster.InProcessCluster;
import com.microjainslee.cluster.RaDialogLeaseCaches;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.Test;

/**
 * ADR 0007 E / D12 on real cluster members: claim-before-send and the transmit
 * fence, including a partitioned owner that is still alive.
 */
public class Ss7TransmitFenceTest {

    private static Ss7Command begin(String dialogId) {
        return new Ss7Command.TcapBegin(dialogId, null, null, 0, List.of(), 0);
    }

    private static Ss7Command cont(String dialogId) {
        return new Ss7Command.TcapContinue(dialogId, null, List.of(), 0);
    }

    @Test
    public void retriedActivityOnAnotherNodeDoesNotSendASecondBegin() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(Files.createTempDirectory("fence1"),
                "fence-retry-" + System.nanoTime(), "f-a", "f-b")) {
            Ss7TransmitFence a = fence(cluster, 0, Map.of());
            Ss7TransmitFence b = fence(cluster, 1, Map.of());

            assertNull("first BEGIN goes out", a.check(begin("gmlc-42")));
            String refused = b.check(begin("gmlc-42"));
            assertNotNull("the retry on node B must be refused, not sent", refused);
            assertTrue(refused.contains("already claimed"));
            assertNull("the owner may repeat its own claim", a.check(begin("gmlc-42")));
        }
    }

    @Test
    public void retryProceedsOnceTheFirstNodeHasLeft() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(Files.createTempDirectory("fence4"),
                "fence-crash-" + System.nanoTime(), "k-a", "k-b", "k-w")) {
            Ss7TransmitFence a = fence(cluster, 0, Map.of());
            Ss7TransmitFence b = fence(cluster, 1, Map.of());
            RaDialogLeaseCaches.create(cluster.node(2), "jss7");                    // witness
            assertNull(a.check(begin("gmlc-99")));
            assertNotNull("refused while node A is alive", b.check(begin("gmlc-99")));

            cluster.kill(0);
            await(() -> !cluster.node(1).isNodePresent("k-a"), 20);

            assertNull("the retry must not wait for garbage collection", b.check(begin("gmlc-99")));
        }
    }

    @Test
    public void partitionedOwnerIsFencedAndMajorityTakesOver() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(Files.createTempDirectory("fence2"),
                "fence-part-" + System.nanoTime(), "g-a", "g-b", "g-w")) {
            RaDialogLeaseCaches leasesA = RaDialogLeaseCaches.create(cluster.node(0), "jss7");
            RaDialogLeaseCaches leasesB = RaDialogLeaseCaches.create(cluster.node(1), "jss7");
            RaDialogLeaseCaches.create(cluster.node(2), "jss7");                   // witness
            try {
                Ss7TransmitFence a = new Ss7TransmitFence(leasesA, id -> "gmlc-7".equals(id) ? 501L : 0L);
                assertTrue(leasesA.tryClaim(Ss7TransmitFence.dialogLeaseKey(501L)));
                assertNull("owner transmits while healthy", a.check(cont("gmlc-7")));

                cluster.isolate(0);
                await(() -> cluster.viewSize(1) == 2 && cluster.viewSize(0) == 1, 30);

                await(() -> a.check(cont("gmlc-7")) != null, 20);
                assertTrue(a.check(cont("gmlc-7")).startsWith("FENCE"));
                await(() -> leasesB.takeOver(Ss7TransmitFence.dialogLeaseKey(501L)), 20);
                assertEquals("g-b", leasesB.lookup(Ss7TransmitFence.dialogLeaseKey(501L)).ownerNodeId());
                cluster.heal(0);
            } finally {
                leasesA.stop();
                leasesB.stop();
            }
        }
    }

    @Test
    public void unknownDialogIsLeftToTheAdapters() throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(Files.createTempDirectory("fence3"),
                "fence-unknown-" + System.nanoTime(), "u-a")) {
            assertNull(fence(cluster, 0, Map.of()).check(cont("never-opened")));
        }
    }

    private static Ss7TransmitFence fence(InProcessCluster cluster, int node, Map<String, Long> otids) {
        RaDialogLeaseCaches leases = RaDialogLeaseCaches.create(cluster.node(node), "jss7");
        return new Ss7TransmitFence(leases, id -> otids.getOrDefault(id, 0L));
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        assertTrue("condition not reached in " + seconds + "s", condition.getAsBoolean());
    }
}
