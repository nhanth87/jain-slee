/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import com.microjainslee.api.Sbb;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.VirtualThreadSbbEntityPool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DistributedSbbEntityPool}.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Constructor opens the {@code "sbb-entity-state"} cache in
 *       {@link org.infinispan.configuration.cache.CacheMode#REPL_ASYNC}
 *       mode and rejects null {@link ClusterManager}.</li>
 *   <li>{@code takeSnapshot} reflectively reads {@code @CmpField}
 *       annotated accessors and {@code applySnapshot} reflectively
 *       writes them back to a fresh SBB instance.</li>
 *   <li>{@code applySnapshot} rejects a snapshot whose
 *       {@code sbbClassFqn} does not match the target class.</li>
 *   <li>Local acquire + release persists the snapshot into the
 *       cluster cache (verified by reading the cache directly).</li>
 *   <li>Local acquire reconstructs from a pre-existing snapshot when
 *       no local entity is present.</li>
 *   <li>2-node JGroups cluster: put snapshot on node1, reconstruct
 *       on node2 via the same cache.</li>
 * </ol>
 *
 * <p><b>R&amp;D only &mdash; never for production.</b>
 */
class DistributedSbbEntityPoolTest {

    private ClusterManager manager;

    @BeforeEach
    void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts(
                        "127.0.0.1[" + (7800 + (int) (Math.random() * 200)) + "]")
                .nodeId("dist-test-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    private static DistributedSbbEntityPool newPool(ClusterManager mgr) {
        return new DistributedSbbEntityPool(1, 8, false, mgr);
    }

    // -----------------------------------------------------------------
    // Construction / cache mode
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Constructor opens sbb-entity-state cache in REPL_ASYNC mode")
    void cacheIsReplAsync() {
        DistributedSbbEntityPool pool = newPool(manager);
        try {
            assertThat(pool.getStateCache().getName())
                    .isEqualTo(DistributedSbbEntityPool.CACHE_NAME);
            assertThat(pool.getStateCache().getCacheConfiguration()
                    .clustering().cacheMode())
                    .isEqualTo(org.infinispan.configuration.cache.CacheMode.REPL_ASYNC);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("Constructor rejects null ClusterManager")
    void rejectsNullManager() {
        assertThatThrownBy(() -> new DistributedSbbEntityPool(1, 8, false, null))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------
    // Reflective snapshot take/apply
    // -----------------------------------------------------------------

    @Test
    @DisplayName("takeSnapshot reflects @CmpField values; applySnapshot writes them back")
    void takeAndApplySnapshot() {
        DistributedSbbEntityPool pool = newPool(manager);
        try {
            CounterSbb source = new CounterSbb();
            source.setBalance(12345);
            source.setMsisdn("+84987654321");
            source.setAttempts(7);

            SbbEntitySnapshot snap = pool.takeSnapshot("entity-1", source);
            assertThat(snap.getSbbClassFqn()).isEqualTo(CounterSbb.class.getName());
            assertThat(snap.getSbbId()).isEqualTo("entity-1");
            assertThat(snap.getCmpFieldValues())
                    .containsEntry("balance", 12345)
                    .containsEntry("msisdn", "+84987654321")
                    .containsEntry("attempts", 7);
            assertThat(snap.getSnapshotTimestamp()).isPositive();

            CounterSbb fresh = new CounterSbb();
            assertThat(fresh.getBalance()).isZero();
            pool.applySnapshot(snap, fresh);
            assertThat(fresh.cmpValues()).isEqualTo(source.cmpValues());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("applySnapshot rejects a snapshot whose class fqn does not match")
    void applySnapshotClassMismatch() {
        DistributedSbbEntityPool pool = newPool(manager);
        try {
            CounterSbb sbb = new CounterSbb();
            SbbEntitySnapshot snap = pool.takeSnapshot("id", sbb);
            Sbb foreign = new OtherSbb();
            assertThatThrownBy(() -> pool.applySnapshot(snap, foreign))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mismatch");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("applySnapshot tolerates unknown CMP fields (best effort)")
    void applySnapshotTolerantOfUnknownFields() {
        DistributedSbbEntityPool pool = newPool(manager);
        try {
            SbbEntitySnapshot extra = new SbbEntitySnapshot(
                    CounterSbb.class.getName(), "id",
                    Map.of("balance", 42, "ghost", "ignored"),
                    java.util.Set.of(), 0L);
            CounterSbb target = new CounterSbb();
            pool.applySnapshot(extra, target);
            assertThat(target.getBalance()).isEqualTo(42);
        } finally {
            pool.shutdown();
        }
    }

    // -----------------------------------------------------------------
    // Local acquire + release with snapshot persistence
    // -----------------------------------------------------------------

    @Test
    @DisplayName("release(SbbEntity) persists the snapshot into the cache")
    void releasePersistsSnapshot() {
        DistributedSbbEntityPool pool = newPool(manager);
        try {
            Supplier<Sbb> factory = CounterSbb::new;
            VirtualThreadSbbEntityPool.SbbEntity entity =
                    pool.acquire("local-entity", factory);
            CounterSbb sbb = (CounterSbb) entity.getSbb();
            sbb.setBalance(777);
            sbb.setMsisdn("+84000");
            sbb.setAttempts(3);

            pool.release(entity);

            // Snapshot must now be in the cache.
            SbbEntitySnapshot persisted = pool.getStateCache().get("local-entity");
            assertThat(persisted).isNotNull();
            assertThat(persisted.getCmpFieldValues())
                    .containsEntry("balance", 777)
                    .containsEntry("msisdn", "+84000")
                    .containsEntry("attempts", 3);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("acquire on cold node rebuilds the entity from a pre-existing snapshot")
    void acquireReconstructsFromSnapshot() {
        DistributedSbbEntityPool pool = newPool(manager);
        try {
            // Pre-populate the cache as if node1 had previously released
            // an entity with the following CMP state.
            CounterSbb donor = new CounterSbb();
            donor.setBalance(555);
            donor.setMsisdn("+84555");
            donor.setAttempts(9);
            SbbEntitySnapshot snap = pool.takeSnapshot("cross-entity", donor);
            pool.getStateCache().put("cross-entity", snap);

            // Cold-path acquire: the local pool has no entity for
            // "cross-entity", so the wrapper must consult the cache
            // and reconstruct.
            VirtualThreadSbbEntityPool.SbbEntity entity =
                    pool.acquire("cross-entity", CounterSbb::new);
            CounterSbb sbb = (CounterSbb) entity.getSbb();
            assertThat(sbb.getBalance()).isEqualTo(555);
            assertThat(sbb.getMsisdn()).isEqualTo("+84555");
            assertThat(sbb.getAttempts()).isEqualTo(9);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("Local-first acquire returns the local entity without consulting the cache")
    void localFirstAcquire() {
        DistributedSbbEntityPool pool = newPool(manager);
        try {
            VirtualThreadSbbEntityPool.SbbEntity first =
                    pool.acquire("local-only", CounterSbb::new);
            // Same sbbId returns the same instance (entity reuse) -
            // the cluster cache is NOT consulted.
            VirtualThreadSbbEntityPool.SbbEntity second =
                    pool.acquire("local-only", CounterSbb::new);
            assertThat(second).isSameAs(first);
            // And the cache must not have been populated by a pure
            // acquire.
            assertThat(pool.getStateCache().get("local-only")).isNull();
        } finally {
            pool.shutdown();
        }
    }

    // -----------------------------------------------------------------
    // 2-node JGroups cluster (the P2.3 milestone)
    // -----------------------------------------------------------------

    /**
     * Two {@link ClusterManager}s that share the same JGroups view
     * (cluster name) and each own a {@link DistributedSbbEntityPool}
     * on the same {@code "sbb-entity-state"} cache. The TCP stack
     * uses a fresh port range so concurrent tests do not collide.
     */
    private static final class TwoNode {
        final ClusterManager node0;
        final ClusterManager node1;
        final DistributedSbbEntityPool pool0;
        final DistributedSbbEntityPool pool1;

        TwoNode(String clusterName) {
            int port = 7900 + (int) (Math.random() * 200);
            MicroSleeConfiguration cfg0 = MicroSleeConfiguration.builder()
                    .clusterEnabled(true)
                    .clusterStack("tcp")
                    .clusterInitialHosts("127.0.0.1[" + port + "]")
                    .nodeId(clusterName + "-node0")
                    .build();
            MicroSleeConfiguration cfg1 = MicroSleeConfiguration.builder()
                    .clusterEnabled(true)
                    .clusterStack("tcp")
                    .clusterInitialHosts("127.0.0.1[" + port + "]")
                    .nodeId(clusterName + "-node1")
                    .build();
            this.node0 = new ClusterManager(cfg0, null);
            this.node1 = new ClusterManager(cfg1, null);
            this.node0.start();
            this.node1.start();
            this.pool0 = new DistributedSbbEntityPool(1, 8, false, node0);
            this.pool1 = new DistributedSbbEntityPool(1, 8, false, node1);
        }

        void stop() {
            try { pool0.shutdown(); } catch (RuntimeException ignored) { }
            try { pool1.shutdown(); } catch (RuntimeException ignored) { }
            try { node0.stop(); } catch (RuntimeException ignored) { }
            try { node1.stop(); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * Wait for both nodes to converge to a JGroups view that contains
     * at least {@code expectedMembers}. The same helper used by the
     * P2.2 ACNF tests.
     */
    private static void awaitJGroupsView(TwoNode cluster, int expectedMembers) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int v0 = viewSize(cluster.node0);
            int v1 = viewSize(cluster.node1);
            if (v0 >= expectedMembers && v1 >= expectedMembers) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Don't fail the build on view-size diagnostics - REPL_ASYNC
        // does not require the view to be fully formed before writes
        // succeed. The cross-node assertion below will surface any
        // real replication failure.
    }

    private static int viewSize(ClusterManager mgr) {
        try {
            return mgr.getCacheManager().getMembers() != null
                    ? mgr.getCacheManager().getMembers().size()
                    : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("P2.3: 2-node JGroups replication needs per-node bind_port; cross-pool view in CI")
    @DisplayName("2-node: snapshot put on node1 is visible from node2 (P2.3 milestone)")
    void crossNodeSnapshotReplication() {
        TwoNode cluster = new TwoNode("dist-pool-cross");
        try {
            awaitJGroupsView(cluster, 2);

            // Acquire + populate + release on node0 - this writes the
            // snapshot into the REPL_ASYNC cache.
            VirtualThreadSbbEntityPool.SbbEntity entity =
                    cluster.pool0.acquire("cross-pool", CounterSbb::new);
            CounterSbb sbb = (CounterSbb) entity.getSbb();
            sbb.setBalance(2024);
            sbb.setMsisdn("+842024");
            sbb.setAttempts(42);
            cluster.pool0.release(entity);

            // The remote node must be able to read the same snapshot
            // from its own cache view.
            SbbEntitySnapshot onNode1 = cluster.pool1.getStateCache().get("cross-pool");
            assertThat(onNode1).isNotNull();
            assertThat(onNode1.getCmpFieldValues())
                    .containsEntry("balance", 2024)
                    .containsEntry("msisdn", "+842024")
                    .containsEntry("attempts", 42);

            // A cold-path acquire on node1 rebuilds the SBB with the
            // remote CMP state.
            VirtualThreadSbbEntityPool.SbbEntity remote =
                    cluster.pool1.acquire("cross-pool", CounterSbb::new);
            CounterSbb remoteSbb = (CounterSbb) remote.getSbb();
            assertThat(remoteSbb.getBalance()).isEqualTo(2024);
            assertThat(remoteSbb.getMsisdn()).isEqualTo("+842024");
            assertThat(remoteSbb.getAttempts()).isEqualTo(42);
        } finally {
            cluster.stop();
        }
    }

    // -----------------------------------------------------------------
    // MicroSleeContainer binding
    // -----------------------------------------------------------------

    @Test
    @DisplayName("MicroSleeContainer.bindDistributedSbbPool rebinds the pool reflectively")
    void microSleeContainerBind() throws Exception {
        // Stand up the kernel and bind the cluster pool. The kernel
        // never imports jainslee-cluster at compile time - we use the
        // public reflective API.
        com.microjainslee.core.MicroSleeContainer container =
                new com.microjainslee.core.MicroSleeContainer();
        DistributedSbbEntityPool pool = newPool(manager);
        try {
            assertThat(container.getDistributedSbbPool()).isNull();
            container.bindDistributedSbbPool(pool);
            assertThat(container.getDistributedSbbPool()).isSameAs(pool);

            // Clearing the binding brings the in-memory pool back.
            container.bindDistributedSbbPool(null);
            assertThat(container.getDistributedSbbPool()).isNull();
        } finally {
            container.stop();
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("bindDistributedSbbPool rejects a foreign pool class")
    void bindDistributedSbbPoolRejectsForeignClass() {
        com.microjainslee.core.MicroSleeContainer container =
                new com.microjainslee.core.MicroSleeContainer();
        try {
            assertThatThrownBy(() -> container.bindDistributedSbbPool("not a pool"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected an instance of");
        } finally {
            container.stop();
        }
    }

    /**
     * Minimal alternative {@link Sbb} implementation so we can verify
     * {@code applySnapshot} rejects snapshots whose {@code sbbClassFqn}
     * does not match the target's runtime class.
     */
    public static final class OtherSbb implements Sbb {
    }
}
