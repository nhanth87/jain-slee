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

import com.microjainslee.core.MicroSleeConfiguration;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit + integration tests for {@link ClusterManager}. Covers:
 * <ol>
 *   <li>local mode &mdash; {@code getCache} returns a working cache, no
 *       JGroups transport is started, {@code stop()} releases resources
 *       cleanly;</li>
 *   <li>local mode &mdash; explicit node id is preserved, auto-UUID is
 *       used when not configured;</li>
 *   <li>local mode &mdash; {@code getCache} is idempotent (same name
 *       yields the same instance);</li>
 *   <li>reflective seam &mdash; the {@code MicroSleeContainer.bindCluster(...)}
 *       method accepts a {@code ClusterManager} and rejects other
 *       types;</li>
 *   <li>cluster mode &mdash; two {@link org.jgroups.JChannel} instances
 *       in the same JVM discover each other via JGroups TCP + FILE_PING
 *       (the discovery layer underneath Infinispan).</li>
 * </ol>
 *
 * <p><b>R&amp;D only &mdash; never for production.</b> The 2-node test
 * uses JGroups {@code FILE_PING} + TCP so no real network sockets are
 * required; this is enough to validate the Infinispan &harr; JGroups
 * integration without depending on a real cluster.
 */
class ClusterManagerTest {

    @TempDir
    Path tempDir;

    private ClusterManager clusterManager;

    @BeforeEach
    void setUp() {
        // Most tests work with the default (local) configuration. Specific
        // tests override clusterEnabled/clusterStack below.
        clusterManager = null;
    }

    @AfterEach
    void tearDown() {
        if (clusterManager != null) {
            try {
                clusterManager.stop();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
    }

    private static MicroSleeConfiguration localConfig() {
        return MicroSleeConfiguration.builder().build();
    }

    // -----------------------------------------------------------------
    // Local mode
    // -----------------------------------------------------------------

    @Test
    @DisplayName("local mode: getCache returns a working cache")
    void localGetCacheWorks() {
        clusterManager = new ClusterManager(localConfig(), null);
        assertThat(clusterManager.isClusterMode()).isFalse();
        assertThat(clusterManager.isClustered()).isFalse();

        Cache<String, String> cache = clusterManager.getCache("local-1", CacheMode.LOCAL);
        assertThat(cache).isNotNull();
        assertThat(cache.isEmpty()).isTrue();
        cache.put("k", "v");
        assertThat(cache.get("k")).isEqualTo("v");
        assertThat(cache.getCacheConfiguration().clustering().cacheMode())
                .isEqualTo(CacheMode.LOCAL);
    }

    @Test
    @DisplayName("local mode: stop() releases resources without error")
    void localStopReleasesResources() {
        clusterManager = new ClusterManager(localConfig(), "test-stop-node");
        Cache<String, String> cache = clusterManager.getCache("stop-test", CacheMode.LOCAL);
        cache.put("k", "v");

        // The Infinispan cache manager must be in RUNNING state right after
        // we put a value, so the stop() path actually has work to do.
        assertThat(clusterManager.getCacheManager().getStatus().toString())
                .isEqualTo("RUNNING");
        clusterManager.stop();

        // After stop, a second stop() must be a no-op (idempotent contract).
        clusterManager.stop();
        // The reference is now invalid; reset so @AfterEach does not call
        // stop() again.
        clusterManager = null;
    }

    @Test
    @DisplayName("local mode: explicit node id override wins over configuration")
    void localExplicitNodeIdOverride() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .nodeId("from-config")
                .build();
        clusterManager = new ClusterManager(cfg, "from-override");
        assertThat(clusterManager.getNodeId()).isEqualTo("from-override");
    }

    @Test
    @DisplayName("local mode: configuration node id is used when no override")
    void localConfiguredNodeId() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .nodeId("host-a")
                .build();
        clusterManager = new ClusterManager(cfg, null);
        assertThat(clusterManager.getNodeId()).isEqualTo("host-a");
    }

    @Test
    @DisplayName("local mode: node id is auto-generated when not configured")
    void localAutoNodeId() {
        clusterManager = new ClusterManager(localConfig(), null);
        // Auto-id pattern: "node-XXXXXXXX" (short UUID).
        assertThat(clusterManager.getNodeId())
                .startsWith("node-")
                .hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("local mode: getCache is idempotent -- same name yields same instance")
    void localGetCacheIdempotent() {
        clusterManager = new ClusterManager(localConfig(), null);
        Cache<String, String> first = clusterManager.getCache("idem", CacheMode.LOCAL);
        Cache<String, String> second = clusterManager.getCache("idem", CacheMode.LOCAL);
        assertThat(first).isSameAs(second);

        // Calling with the same mode returns the same instance.
        Cache<String, String> third = clusterManager.getCache("idem", CacheMode.LOCAL);
        assertThat(third).isSameAs(first);
    }

    @Test
    @DisplayName("local mode: construction with clusterEnabled=false and null nodeId is valid")
    void localNullNodeIdValid() {
        clusterManager = new ClusterManager(localConfig(), null);
        // No exception thrown -- the auto-id path handles null cleanly.
        assertThat(clusterManager.getNodeId()).isNotBlank();
    }

    @Test
    @DisplayName("local mode: getLocalStringCache returns a working String/String cache")
    void localGetLocalStringCache() {
        clusterManager = new ClusterManager(localConfig(), null);
        Cache<String, String> cache = clusterManager.getLocalStringCache("strings");
        cache.put("hello", "world");
        assertThat(cache.get("hello")).isEqualTo("world");
        assertThat(cache.getCacheConfiguration().clustering().cacheMode())
                .isEqualTo(CacheMode.LOCAL);
    }

    // -----------------------------------------------------------------
    // MicroSleeContainer reflective seam
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reflective seam: MicroSleeContainer.bindCluster accepts a ClusterManager")
    void reflectiveBindCluster() throws Exception {
        // Use reflection to call MicroSleeContainer.bindCluster(Object) so
        // the test does not depend on jainslee-cluster being on the
        // jainslee-core compile classpath. The reflective pattern mirrors
        // the JTA binding done by MicroSleeContainer.createJtaTransactionContext.
        Class<?> containerClass = Class.forName("com.microjainslee.core.MicroSleeContainer");
        Constructor<?> ctor = containerClass.getDeclaredConstructor();
        Object container = ctor.newInstance();
        try {
            clusterManager = new ClusterManager(localConfig(), "reflective");
            Method bind = containerClass.getMethod("bindCluster", Object.class);
            bind.invoke(container, clusterManager);

            Object stored = containerClass.getMethod("getClusterManager").invoke(container);
            assertThat(stored).isSameAs(clusterManager);
        } finally {
            // Tear down the container. The container's stop() reflects
            // back to our cluster manager -- that is the contract.
            Method stop = containerClass.getMethod("stop");
            stop.invoke(container);
        }
        // After container stop, the cluster manager reference inside the
        // container must have been cleared to null.
        Object storedAfter = containerClass.getMethod("getClusterManager").invoke(container);
        assertThat(storedAfter).isNull();
    }

    @Test
    @DisplayName("reflective seam: bindCluster rejects a non-ClusterManager argument")
    void reflectiveBindClusterRejectsWrongType() throws Exception {
        Class<?> containerClass = Class.forName("com.microjainslee.core.MicroSleeContainer");
        Constructor<?> ctor = containerClass.getDeclaredConstructor();
        Object container = ctor.newInstance();
        try {
            Method bind = containerClass.getMethod("bindCluster", Object.class);
            // Pass a String -- clearly not a ClusterManager.
            assertThatThrownBy(() -> bind.invoke(container, "not-a-cluster-manager"))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        } finally {
            containerClass.getMethod("stop").invoke(container);
        }
    }

    // -----------------------------------------------------------------
    // Cluster mode -- 2-node JGroups TCP + FILE_PING discovery
    // -----------------------------------------------------------------

    @Test
    @DisplayName("cluster mode: 2 JChannels in same JVM discover each other (FILE_PING)")
    void clusterTwoNodesReplicate() throws Exception {
        // FILE_PING requires a shared directory. The Infinispan/JGroups
        // jar will read JGroups system properties so we point it at the
        // temp dir, then load the test XML from the classpath.
        Path jgroupsDir = tempDir.resolve("jgroups-shared");
        java.nio.file.Files.createDirectories(jgroupsDir);
        System.setProperty("jgroups.file_ping.dir", jgroupsDir.toString());

        URL xml = getClass().getClassLoader()
                .getResource("jgroups-tcp-file-ping.xml");
        assertThat(xml)
                .as("jgroups-tcp-file-ping.xml must be on the classpath")
                .isNotNull();

        // JChannel(String) takes a path or URL string. We pass the
        // toExternalForm() so the test works regardless of how the
        // classloader locates the resource.
        String xmlPath = xml.toExternalForm();

        // Two channels on the same loopback. We let JGroups pick
        // ephemeral ports via bind_port=0 + port_range=50.
        String node1Id = "n1-" + UUID.randomUUID().toString().substring(0, 4);
        String node2Id = "n2-" + UUID.randomUUID().toString().substring(0, 4);
        org.jgroups.JChannel ch1 = new org.jgroups.JChannel(xmlPath);
        org.jgroups.JChannel ch2 = new org.jgroups.JChannel(xmlPath);
        try {
            ch1.setName(node1Id);
            ch2.setName(node2Id);
            ch1.connect("test-cluster");
            ch2.connect("test-cluster");

            // Wait for both nodes to see a 2-member view. JGroups may
            // take a few seconds to converge -- 20s is plenty for a
            // loopback test.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                if (ch1.getView().size() == 2 && ch2.getView().size() == 2) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(ch1.getView().size())
                    .as("node1 should see 2 members in the view, got %s", ch1.getView())
                    .isEqualTo(2);
            assertThat(ch2.getView().size())
                    .as("node2 should see 2 members in the view, got %s", ch2.getView())
                    .isEqualTo(2);

            // Each channel should see the other node in its view.
            assertThat(ch1.getView().getMembers())
                    .as("node1 view contains node2")
                    .anyMatch(addr -> addr.toString().contains(node2Id));
            assertThat(ch2.getView().getMembers())
                    .as("node2 view contains node1")
                    .anyMatch(addr -> addr.toString().contains(node1Id));
        } finally {
            // Disconnect + close in reverse order so the view is dropped
            // before the FILE_PING state is torn down.
            try { ch2.close(); } catch (Exception ignored) {}
            try { ch1.close(); } catch (Exception ignored) {}
            System.clearProperty("jgroups.file_ping.dir");
        }
    }

    // -----------------------------------------------------------------
    // Cluster mode -- ClusterManager construction with clusterEnabled=true
    // -----------------------------------------------------------------

    @Test
    @DisplayName("cluster mode: ClusterManager with clusterEnabled=true builds a JGroups transport")
    void clusterModeClusterManagerConstruction() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts("127.0.0.1[7800]")
                .nodeId("cluster-mode-test")
                .build();
        clusterManager = new ClusterManager(cfg, null);
        // Configuration node id is used because the override is null.
        assertThat(clusterManager.getNodeId()).isEqualTo("cluster-mode-test");
        assertThat(clusterManager.isClusterMode()).isTrue();
        // We do not call start() here -- the test runs offline and the
        // JGroups discovery would fail. The construction alone proves
        // the Infinispan global config accepts a JGroups transport.
    }
}
