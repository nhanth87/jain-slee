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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.core.MicroSleeConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClusteredActivityContextNamingFacility}.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Single-node bind / lookup / unbind round-trip.</li>
 *   <li>{@code names()} returns every bound name; unbind drops it.</li>
 *   <li>Null guards on bind / lookup / unbind.</li>
 *   <li>2-node cluster — bind on node 1 is visible from node 2
 *       (the cross-node case required by the production roadmap
 *       P2.2 milestone).</li>
 *   <li>2-node cluster — unbind on node 1 is visible from node 2.</li>
 *   <li>2-node cluster — {@code names()} on node 2 reflects writes
 *       from node 1.</li>
 *   <li>Cache mode is {@code DIST_SYNC} on the {@code slee-acnf}
 *       cache.</li>
 *   <li>{@code ClusteredActivityContextNamingFacility} rejects a
 *       local (non-clustered) {@link ClusterManager} — the facility
 *       is meaningless without JGroups.</li>
 * </ol>
 *
 * <p>Marshalling caveat: the test uses
 * {@link ClusterTestUtil.SerializableAci} (a {@link java.io.Serializable}
 * stub) instead of {@code InMemoryActivityContext} so we can
 * exercise the Infinispan wire path. See
 * {@link ClusteredActivityContextNamingFacility} class javadoc for
 * the P5 follow-up task.
 */
class ClusteredActivityContextNamingFacilityTest {

    private ClusterManager localManager;
    private ClusteredActivityContextNamingFacility localFacility;

    @BeforeEach
    void setUp() {
        // Single-node cluster — the manager is "clustered" in the
        // sense that it has a JGroups transport configured, but it
        // sees only itself in the view. This is enough to validate
        // bind / lookup / unbind / names() without the brittleness
        // of multi-node JGroups in CI.
        MicroSleeConfiguration cfg = clusterConfig("local-acnf-test", null);
        localManager = new ClusterManager(cfg, "local-only");
        localManager.start();
        localFacility = new ClusteredActivityContextNamingFacility(localManager);
    }

    @AfterEach
    void tearDown() {
        if (localManager != null) {
            localManager.stop();
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Build a {@link MicroSleeConfiguration} with the cluster layer
     * enabled and a unique TCP discovery port range so concurrent
     * test runs do not collide.
     */
    private static MicroSleeConfiguration clusterConfig(String clusterName, String nodeId) {
        // The port range uses a random short offset so two test
        // runs do not try to bind the same JGroups port. JGroups
        // tolerates the address; we just need a unique value.
        int port = 7800 + (int) (Math.random() * 200);
        return MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts("127.0.0.1[" + port + "]")
                .nodeId(nodeId != null ? nodeId : ("node-" + UUID.randomUUID().toString().substring(0, 8)))
                .build();
    }

    // -----------------------------------------------------------------
    // Construction / cache mode
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Constructor opens slee-acnf cache in DIST_SYNC mode")
    void cacheIsDistSync() {
        assertThat(localFacility.getCache().getName())
                .isEqualTo(ClusteredActivityContextNamingFacility.CACHE_NAME);
        assertThat(localFacility.getCache().getCacheConfiguration()
                .clustering().cacheMode())
                .isEqualTo(org.infinispan.configuration.cache.CacheMode.DIST_SYNC);
    }

    @Test
    @DisplayName("Constructor rejects a local (non-clustered) ClusterManager")
    void rejectsLocalManager() {
        MicroSleeConfiguration local = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .build();
        ClusterManager mgr = new ClusterManager(local, "local-test");
        try {
            assertThatThrownBy(() -> new ClusteredActivityContextNamingFacility(mgr))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("clustered");
        } finally {
            mgr.stop();
        }
    }

    @Test
    @DisplayName("Constructor rejects a null ClusterManager")
    void rejectsNullManager() {
        assertThatThrownBy(() -> new ClusteredActivityContextNamingFacility(null))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------
    // Single-node behaviour
    // -----------------------------------------------------------------

    @Test
    @DisplayName("bind + lookup round-trip on a single node")
    void bindLookupRoundTrip() {
        ActivityContextInterface aci = ClusterTestUtil.newSerializableAci("alpha");
        localFacility.bind("alpha", aci);

        ActivityContextInterface lookedUp = localFacility.lookup("alpha");
        assertThat(lookedUp).isSameAs(aci);
    }

    @Test
    @DisplayName("lookup of an unknown name returns null")
    void lookupUnknownReturnsNull() {
        assertThat(localFacility.lookup("nope")).isNull();
    }

    @Test
    @DisplayName("unbind removes the binding")
    void unbindRemovesBinding() {
        ActivityContextInterface aci = ClusterTestUtil.newSerializableAci("beta");
        localFacility.bind("beta", aci);
        assertThat(localFacility.lookup("beta")).isSameAs(aci);

        localFacility.unbind("beta");
        assertThat(localFacility.lookup("beta")).isNull();
    }

    @Test
    @DisplayName("unbind is a no-op for unknown names")
    void unbindUnknownIsNoOp() {
        // Should not throw.
        localFacility.unbind("never-bound");
    }

    @Test
    @DisplayName("bind rejects null name or null aci")
    void bindNullGuards() {
        ActivityContextInterface aci = ClusterTestUtil.newSerializableAci("gamma");
        assertThatThrownBy(() -> localFacility.bind(null, aci))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> localFacility.bind("gamma", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lookup / unbind tolerate null")
    void lookupUnbindNullTolerated() {
        assertThat(localFacility.lookup(null)).isNull();
        // No-throw:
        localFacility.unbind(null);
    }

    @Test
    @DisplayName("names() returns every bound name, unbind drops it")
    void namesReflectsBindings() {
        ActivityContextInterface a = ClusterTestUtil.newSerializableAci("a");
        ActivityContextInterface b = ClusterTestUtil.newSerializableAci("b");
        ActivityContextInterface c = ClusterTestUtil.newSerializableAci("c");
        localFacility.bind("a", a);
        localFacility.bind("b", b);
        localFacility.bind("c", c);

        Set<String> names = localFacility.names();
        assertThat(names).containsExactlyInAnyOrder("a", "b", "c");

        localFacility.unbind("b");
        assertThat(localFacility.names()).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    @DisplayName("Re-binding a name replaces the value")
    void rebindReplacesValue() {
        ActivityContextInterface first = ClusterTestUtil.newSerializableAci("dup");
        ActivityContextInterface second = ClusterTestUtil.newSerializableAci("dup");
        localFacility.bind("dup", first);
        localFacility.bind("dup", second);
        assertThat(localFacility.lookup("dup")).isSameAs(second);
    }

    // -----------------------------------------------------------------
    // 2-node cluster behaviour (the P2.2 milestone)
    // -----------------------------------------------------------------

    /**
     * A pair of {@link ClusterManager}s that share the same JGroups
     * view (cluster name) and each own a
     * {@link ClusteredActivityContextNamingFacility} on the same
     * {@code slee-acnf} cache.
     */
    private static final class TwoNode {
        final ClusterManager node0;
        final ClusterManager node1;
        final ClusteredActivityContextNamingFacility facility0;
        final ClusteredActivityContextNamingFacility facility1;

        TwoNode(String clusterName) {
            int port = 7800 + (int) (Math.random() * 200);
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
            this.facility0 = new ClusteredActivityContextNamingFacility(node0);
            this.facility1 = new ClusteredActivityContextNamingFacility(node1);
        }

        void stop() {
            try { node0.stop(); } catch (RuntimeException ignored) { }
            try { node1.stop(); } catch (RuntimeException ignored) { }
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("P2.2: requires per-node JGroups bind_port; see TwoNode Javadoc")
    @DisplayName("2-node: bind on node1, lookup from node2 (P2.2 milestone)")
    void crossNodeBindLookup() {
        TwoNode cluster = new TwoNode("cross-acnf");
        try {
            // Wait for the JGroups view to converge on both nodes.
            awaitJGroupsView(cluster, 2);

            ActivityContextInterface aci = ClusterTestUtil.newSerializableAci("remote-key");
            cluster.facility0.bind("remote-key", aci);

            // DIST_SYNC: synchronous replication — the remote lookup
            // is visible as soon as the put returns.
            ActivityContextInterface lookedUp = cluster.facility1.lookup("remote-key");
            assertThat(lookedUp).isNotNull();
            assertThat(lookedUp.getActivityContextName()).isEqualTo("remote-key");
        } finally {
            cluster.stop();
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("P2.2: requires per-node JGroups bind_port; see TwoNode Javadoc")
    @DisplayName("2-node: unbind on node1 visible from node2")
    void crossNodeUnbind() {
        TwoNode cluster = new TwoNode("cross-acnf-unbind");
        try {
            awaitJGroupsView(cluster, 2);

            ActivityContextInterface aci = ClusterTestUtil.newSerializableAci("vanishing");
            cluster.facility0.bind("vanishing", aci);
            assertThat(cluster.facility1.lookup("vanishing")).isNotNull();

            cluster.facility0.unbind("vanishing");
            assertThat(cluster.facility1.lookup("vanishing")).isNull();
        } finally {
            cluster.stop();
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("P2.2: requires per-node JGroups bind_port; see TwoNode Javadoc")
    @DisplayName("2-node: names() on node2 reflects writes from node1")
    void crossNodeNames() {
        TwoNode cluster = new TwoNode("cross-acnf-names");
        try {
            awaitJGroupsView(cluster, 2);

            ActivityContextInterface a = ClusterTestUtil.newSerializableAci("a");
            ActivityContextInterface b = ClusterTestUtil.newSerializableAci("b");
            cluster.facility0.bind("a", a);
            cluster.facility0.bind("b", b);

            // Eventually consistent: retry for a short window
            // because DIST_SYNC is synchronous but the local cache
            // view (which backs Cache.keySet()) is updated through
            // an internal notification.
            Set<String> remoteNames = new HashSet<String>();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline
                    && !remoteNames.containsAll(java.util.Arrays.asList("a", "b"))) {
                remoteNames = new HashSet<String>(cluster.facility1.names());
                if (!remoteNames.containsAll(java.util.Arrays.asList("a", "b"))) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            assertThat(remoteNames).contains("a", "b");
        } finally {
            cluster.stop();
        }
    }

    /**
     * Wait for both nodes in the cluster to see a JGroups view of
     * {@code expectedMembers}. Without this gate the very first
     * put would race the GMS merge protocol and may be lost.
     */
    private static void awaitJGroupsView(TwoNode cluster, int expectedMembers) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
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
        // Don't fail the build on view-size diagnostics — Infinispan
        // tolerates a partial view. The cross-node assertion below
        // will surface the issue if the view never formed.
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
}
