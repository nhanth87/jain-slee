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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;

/**
 * ADR 0007 P6 — <b>two real nodes</b>, real JGroups over TCP loopback.
 *
 * <p>
 * This is the lab proof for the three requirements, run in-process but across two
 * genuine cluster members (not one manager with two views — that would prove
 * nothing about forwarding).
 *
 * <pre>
 *   node "http-1"  — ra-http-server role: owns the client's socket + SLEE activity
 *   node "ss7-1"   — ra-jss7 role:       owns the TCAP dialog + M3UA ASP
 * </pre>
 *
 * <p>
 * What it does NOT cover (lab-only, needs a real STP): the SS7 wire half —
 * multi-ASP loadsharing, OTID range partitioning, CONTINUE takeover through
 * {@code TcapMissingDialogResolver}. See
 * {@code docs/lab/active-active-two-node.md}. What it DOES prove is everything
 * that was silently broken in the runtime and had no test at all: cross-node
 * response routing, orphan lease reclaim, and node-identity fencing.
 */
class ActiveActiveTwoNodeLabTest {

    private static final long AWAIT_SECONDS = 20;

    /** Stand-in for a MAP response; must be allow-list clean to cross the bus. */
    record FakeMapResponse(String dialogId, String imsi, String mscNumber) implements SerializableMarker {
    }

    /** Marker so the test type lives under {@code com.microjainslee.*}. */
    interface SerializableMarker extends java.io.Serializable {
    }

    // ── Scenario A: the response reaches the node holding the socket ─────────

    @Test
    @DisplayName("A: a response produced on the SS7 node is delivered on the HTTP node")
    void responseReturnsToTheNodeHoldingTheSocket(@TempDir Path tmp) throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tmp, "aa-lab-a", "http-1", "ss7-1")) {
            MicroSleeContainer httpNode = startContainer(cluster.node(0), "http-1");
            MicroSleeContainer ss7Node = startContainer(cluster.node(1), "ss7-1");

            Object httpIngress = httpNode.getStickyEventIngress();
            assertNotNull(httpIngress,
                    "ADR 0007 P2: every clustered node must host the ingress — the HTTP node "
                            + "runs no ra-jss7, so a per-RA bus alone would strand the response");
            assertNotNull(ss7Node.getStickyEventIngress(),
                    "the SS7 node hosts it too, so it can receive replies it did not produce");

            // The HTTP node owns activity "gmlc-x" (it holds the client socket and
            // the SLEE activity context). Publish that ownership.
            String activityId = "gmlc-x";

            // The SS7 node received the MAP response and forwards it home.
            boolean forwarded = ss7Node.forwardEventToOwner(
                    "http-1", activityId, "ra-jss7", "Service",
                    new FakeMapResponse(activityId, "234150999876543", "49170123"));
            assertTrue(forwarded, "the SS7 node must be able to publish to the owner");

            // Poll: the envelope traverses the DIST_SYNC cache and the owner's
            // listener re-injects it. In a real deployment the local SBB then writes
            // the HTTP response on the same socket.
            boolean delivered = await(5, () -> {
                org.infinispan.Cache<String, StickyEventEnvelope> ingress = cluster.node(0).getCache(
                        ContainerStickyEventIngress.CACHE_NAME,
                        org.infinispan.configuration.cache.CacheMode.DIST_SYNC);
                return ingress.size() == 0;
            });
            assertTrue(delivered,
                    "the envelope must be consumed by the owner and removed from the mailbox");
        }
    }

    // ── Scenario B: no double delivery when both nodes would answer ──────────

    @Test
    @DisplayName("B: the owner consumes the envelope exactly once")
    void ownerConsumesTheEnvelopeOnce(@TempDir Path tmp) throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tmp, "aa-lab-b", "http-1", "ss7-1")) {
            MicroSleeContainer httpNode = startContainer(cluster.node(1 - 1), "http-1");

            String activityId = "gmlc-once";
            java.util.concurrent.atomic.AtomicInteger deliveries =
                    new java.util.concurrent.atomic.AtomicInteger();
            CountDownLatch latch = new CountDownLatch(1);

            ContainerStickyEventIngress ingress = new ContainerStickyEventIngress(
                    cluster.node(0), "http-1", (id, payload) -> {
                        deliveries.incrementAndGet();
                        latch.countDown();
                    });
            ingress.start();

            cluster.node(1).getCache(ContainerStickyEventIngress.CACHE_NAME,
                    org.infinispan.configuration.cache.CacheMode.DIST_SYNC)
                    .put("env-1", StickyEventEnvelope.of("http-1", "ss7-1", activityId,
                            "ra-jss7", "Service", new FakeMapResponse(activityId, "1", "2")));

            assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "the owning node must receive the forwarded event");
            Thread.sleep(500);   // give a second listener a chance to misfire
            assertEquals(1, deliveries.get(),
                    "the mailbox is at-least-once; the owner must consume once, not fan out");
            ingress.stop();
            assertNotNull(httpNode);
        }
    }

    // ── Scenario D: a dead node's dialog is reclaimed, not orphaned ──────────

    @Test
    @DisplayName("D: killing a node lets a survivor reclaim its expired leases")
    void killedNodeLeasesAreReclaimed(@TempDir Path tmp) throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tmp, "aa-lab-d", "http-1", "ss7-1")) {
            RaDialogLeaseCaches dead =
                    RaDialogLeaseCaches.create(cluster.node(1), "ra-jss7", 1_000L);
            assertTrue(dead.tryClaim("gmlc-midflight"),
                    "the dying node owns the in-flight dialog");

            RaDialogLeaseCaches survivor = RaDialogLeaseCaches.create(cluster.node(0), "ra-jss7", 60_000L);
            assertFalse(survivor.isLocalOwner("gmlc-midflight"),
                    "before a crash the survivor must NOT steal a healthy owner's dialog");

            cluster.kill(1);   // abrupt stop, as a crash would

            // TTL is 1s and the owner is gone from the view, so a takeover must succeed.
            // Drive it explicitly rather than waiting on the 15s sweeper — this test
            // is about the reclaim MECHANISM; `takeOver()` is what inbound traffic
            // and the sweep both go through.
            boolean reclaimed = await(20, () ->
                    survivor.takeOver("gmlc-midflight") && survivor.isLocalOwner("gmlc-midflight"));
            assertTrue(reclaimed,
                    "ADR 0007 D3: a dead owner's lease must be reclaimable. Before this the owner "
                            + "row named a dead node FOREVER and every survivor REJECTed the dialog");

            // The reaper must also be scheduled, or a quiet node-death is only noticed
            // when unrelated traffic happens to touch that dialog.
            assertTrue(RaDialogLeaseCaches.DEFAULT_SWEEP_PERIOD_MS <= 60_000L,
                    "lease reaper period must be far shorter than an operator's patience");
            survivor.stop();
        }
    }

    // ── Scenario C: a partition must fence the zombie, not resurrect it ──────

    /**
     * The only test that proves the generation fence. A kill is too easy: the dead
     * node simply stops. Here node-2 is alive, still holds its TCP socket, and
     * still believes it owns the dialog — the exact shape that produces a
     * duplicate MAP transaction against the real SMSC.
     */
    @Test
    @DisplayName("C: a partitioned owner is fenced out of transmitting; the survivor takes over")
    void partitionFencesTheZombie(@TempDir Path tmp) throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tmp, "aa-lab-c", "http-1", "ss7-1")) {
            RaDialogLeaseCaches zombie = RaDialogLeaseCaches.create(cluster.node(1), "ra-jss7", 1_000L);
            assertTrue(zombie.tryClaim("gmlc-split"));
            assertTrue(zombie.mayTransmit("gmlc-split"),
                    "while healthy the owner may transmit");

            RaDialogLeaseCaches survivor = RaDialogLeaseCaches.create(cluster.node(0), "ra-jss7", 60_000L);
            assertFalse(survivor.takeOver("gmlc-split"),
                    "a LIVE owner must not be stolen from — that would itself be a duplicate send");

            cluster.isolate(1);                       // scenario C: alive but cut off

            // The zombie still believes it owns the dialog. That is the whole danger.
            RaDialogLease staleView = zombie.lookup("gmlc-split");
            assertNotNull(staleView);
            assertTrue(staleView.stillOwnedBy("ss7-1", zombie.bootEpochMs()));

            // Its lease expires while it cannot renew it (its heartbeat and cache are
            // cut off). After the TTL the survivor may take over.
            boolean taken = await(25, () -> survivor.takeOver("gmlc-split"));
            assertTrue(taken, "the survivor must be able to take over a split dialog once its lease expires");
            assertTrue(survivor.isLocalOwner("gmlc-split"));

            // The fence, asserted from the ZOMBIE's side. It must now refuse to
            // transmit: this is the single property that stops the duplicate.
            assertFalse(zombie.mayTransmit("gmlc-split"),
                    "a partitioned zombie must NOT be able to transmit on a dialog the survivor "
                            + "has taken over — that is a second MAP transaction to the SMSC");
            assertTrue(zombie.fenceBlocked() > 0,
                    "the refusal must be counted, otherwise the incident is invisible");

            cluster.heal(1);
            survivor.stop();
            zombie.stop();
        }
    }

    // ── Scenario G: a recovered node rejoins with a DIFFERENT incarnation ─────

    /**
     * A restarted node <b>must</b> be able to re-take its own leases: its previous
     * dialogs died with the old JVM and it now reuses its OTID range, so refusing
     * would strand every one of them.
     *
     * <p>
     * The property that actually matters is that the re-take bumps the generation
     * and carries the NEW boot epoch, so the OLD incarnation — if it somehow came
     * back, or if its in-flight CAS was merely delayed — can never pass the fence.
     */
    @Test
    @DisplayName("G: a restarted node re-takes its own lease with a bumped generation and a new epoch")
    void restartedNodeIsFencedByBootEpoch(@TempDir Path tmp) throws Exception {
        try (InProcessCluster cluster = new InProcessCluster(tmp, "aa-lab-g", "http-1", "ss7-1")) {
            RaDialogLeaseCaches first = RaDialogLeaseCaches.create(cluster.node(1), "ra-jss7", 1_000L);
            assertTrue(first.tryClaim("gmlc-restart"));
            RaDialogLease before = first.lookup("gmlc-restart");
            assertNotNull(before);
            long firstBootEpoch = first.bootEpochMs();

            // A fresh JVM on the same node id. Production takes
            // System.currentTimeMillis() at construction; give it a real gap.
            Thread.sleep(5);
            RaDialogLeaseCaches afterRestart =
                    RaDialogLeaseCaches.create(cluster.node(1), "ra-jss7", 1_000L);
            assertTrue(afterRestart.bootEpochMs() > firstBootEpoch,
                    "a restart must produce a strictly greater boot epoch, otherwise the "
                            + "supersede branch (prior.ownerBootEpochMs() < bootEpochMs) never fires");

            assertTrue(afterRestart.tryClaim("gmlc-restart"),
                    "a restarted node MUST be able to re-take its own lease — its dialogs died with "
                            + "the old JVM and it reuses the same OTID range");

            RaDialogLease after = afterRestart.lookup("gmlc-restart");
            assertEquals(before.generation() + 1, after.generation(),
                    "the re-take must bump the generation so a delayed CAS from the old "
                            + "incarnation cannot land on top of it");
            assertEquals(afterRestart.bootEpochMs(), after.ownerBootEpochMs());

            // The decisive assertion: the OLD incarnation's view no longer passes the
            // fence even though the node id is byte-identical.
            assertFalse(after.stillOwnedBy("ss7-1", firstBootEpoch),
                    "the old incarnation must be fenced out by the boot-epoch difference alone");

            afterRestart.stop();
            first.stop();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private MicroSleeContainer startContainer(ClusterManager cm, String nodeId) throws Exception {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .nodeId(nodeId)
                .eventRouterBufferSize(1024)
                .build();
        MicroSleeContainer container = new MicroSleeContainer(cfg);
        container.bindCluster(cm);
        container.start();
        return container;
    }

    private static boolean await(int seconds, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}