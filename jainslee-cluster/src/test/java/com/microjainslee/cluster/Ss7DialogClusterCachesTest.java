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

import com.microjainslee.core.MicroSleeConfiguration;
import org.infinispan.configuration.cache.CacheMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 skeleton: SS7 dialog meta / ownership caches on {@link ClusterManager}.
 */
class Ss7DialogClusterCachesTest {

    private ClusterManager manager;
    private Ss7DialogClusterCaches caches;

    @BeforeEach
    void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("ss7-dlg-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        caches = Ss7DialogClusterCaches.ensureCaches(manager);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void ensureCachesIsIdempotentAndUsesLocalModeWhenNotClustered() {
        Ss7DialogClusterCaches again = Ss7DialogClusterCaches.ensureCaches(manager);
        assertThat(again.metaCache()).isSameAs(caches.metaCache());
        assertThat(again.ownerCache()).isSameAs(caches.ownerCache());
        assertThat(manager.isClusterMode()).isFalse();
        assertThat(caches.metaCache().getCacheConfiguration().clustering().cacheMode())
                .isEqualTo(CacheMode.LOCAL);
        assertThat(caches.ownerCache().getCacheConfiguration().clustering().cacheMode())
                .isEqualTo(CacheMode.LOCAL);
        assertThat(caches.byRemoteCache().getCacheConfiguration().clustering().cacheMode())
                .isEqualTo(CacheMode.LOCAL);
        assertThat(caches.stickyCommandCache().getCacheConfiguration().clustering().cacheMode())
                .isEqualTo(CacheMode.LOCAL);
    }

    @Test
    void ensureCachesUsesReplSyncWhenClustered() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts("127.0.0.1[" + (7900 + (int) (Math.random() * 200)) + "]")
                .nodeId("ss7-repl-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        ClusterManager clustered = new ClusterManager(cfg, null);
        try {
            clustered.start();
            Ss7DialogClusterCaches c = Ss7DialogClusterCaches.ensureCaches(clustered);
            assertThat(c.metaCache().getCacheConfiguration().clustering().cacheMode())
                    .isEqualTo(CacheMode.REPL_SYNC);
            assertThat(c.ownerCache().getCacheConfiguration().clustering().cacheMode())
                    .isEqualTo(CacheMode.REPL_SYNC);
            assertThat(c.stickyCommandCache().getCacheConfiguration().clustering().cacheMode())
                    .isEqualTo(CacheMode.DIST_SYNC);
        } finally {
            clustered.stop();
        }
    }

    @Test
    void putGetMetaAndRemoteIndex() {
        byte[] remoteOtid = new byte[] {0x01, 0x02, 0x03, 0x04};
        TcapDialogMeta meta = new TcapDialogMeta(
                "dlg-1",
                42L,
                remoteOtid,
                1, 8, 2, 6,
                "Active",
                manager.getNodeId(),
                "ra-jss7",
                0L,
                "aci-dlg-1",
                "corr-1",
                System.currentTimeMillis());
        caches.putMeta(meta);

        TcapDialogMeta loaded = caches.metaCache().get("dlg-1");
        assertThat(loaded).isEqualTo(meta);
        assertThat(caches.byRemoteCache().get(meta.remoteIndexKey())).isEqualTo("dlg-1");
    }

    @Test
    void ownershipCasBumpsGeneration() {
        long t0 = System.currentTimeMillis();
        RaDialogOwner first = new RaDialogOwner("dlg-own", "node-a", "ra-1", 0L, t0);
        assertThat(caches.tryPutOwnerIfAbsent(first)).isTrue();
        assertThat(caches.tryPutOwnerIfAbsent(
                new RaDialogOwner("dlg-own", "node-b", "ra-2", 0L, t0))).isFalse();

        assertThat(caches.tryClaimOwnership(first, "node-b", "ra-2", t0 + 1)).isTrue();
        RaDialogOwner after = caches.ownerCache().get("dlg-own");
        assertThat(after.ownerNodeId()).isEqualTo("node-b");
        assertThat(after.raName()).isEqualTo("ra-2");
        assertThat(after.generation()).isEqualTo(1L);

        // Stale expected (generation 0) must fail.
        assertThat(caches.tryClaimOwnership(first, "node-c", "ra-3", t0 + 2)).isFalse();
        assertThat(caches.ownerCache().get("dlg-own").generation()).isEqualTo(1L);
    }

    @Test
    void removeMetaAndOwner() {
        TcapDialogMeta meta = new TcapDialogMeta(
                "rm-1", 1L, new byte[] {1}, 1, 8, 2, 6, "Active",
                manager.getNodeId(), "ra", 0L, "aci", null, 1L);
        caches.putMeta(meta);
        caches.putSnapshot(new TcapDialogSnapshotPayload(
                "rm-1", 1L, new byte[] {1},
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(1, 8),
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(2, 6),
                "Active", null, 1L, 0, 8, 2, 0, false, null, 1L));
        caches.tryPutOwnerIfAbsent(new RaDialogOwner("rm-1", manager.getNodeId(), "ra", 0L, 1L));
        caches.removeMeta("rm-1");
        caches.removeOwner("rm-1");
        assertThat(caches.metaCache().get("rm-1")).isNull();
        assertThat(caches.getSnapshot("rm-1")).isNull();
        assertThat(caches.getOwner("rm-1")).isNull();
        assertThat(caches.byRemoteCache().get(meta.remoteIndexKey())).isNull();
    }

    @Test
    void putGetSnapshot() {
        TcapDialogSnapshotPayload snap = new TcapDialogSnapshotPayload(
                "snap-1",
                7L,
                new byte[] {1, 2},
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(1, 8),
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(2, 6),
                "Active",
                null,
                System.nanoTime(),
                0,
                8,
                2,
                1,
                false,
                new boolean[8],
                System.currentTimeMillis());
        caches.putSnapshot(snap);
        assertThat(caches.getSnapshot("snap-1")).isEqualTo(snap);
        assertThat(caches.snapshotCache().getCacheConfiguration().clustering().cacheMode())
                .isEqualTo(CacheMode.LOCAL);
    }

    @Test
    void marshallingAllowListAcceptsSs7DialogTypes() {
        assertThat(MarshallingAllowList.isAllowedClass(TcapDialogMeta.class)).isTrue();
        assertThat(MarshallingAllowList.isAllowedClass(RaDialogOwner.class)).isTrue();
        assertThat(MarshallingAllowList.isAllowedClass(TcapDialogSnapshotPayload.class)).isTrue();
        MarshallingAllowList.assertMarshallable("meta", new TcapDialogMeta(
                "k", 1L, new byte[] {1}, 1, 8, 2, 6, "Idle",
                "n", "ra", 0L, null, null, 1L));
        MarshallingAllowList.assertMarshallable(
                "owner", new RaDialogOwner("d", "n", "ra", 0L, 1L));
        MarshallingAllowList.assertMarshallable("snap", new TcapDialogSnapshotPayload(
                "k", 1L, null,
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(1, 8),
                null, "Idle", null, 0L, 0, 8, 0, 0, false, null, 1L));
    }
}
