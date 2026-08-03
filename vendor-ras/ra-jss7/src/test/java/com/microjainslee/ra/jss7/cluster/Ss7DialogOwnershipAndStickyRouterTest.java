/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.RaDialogOwner;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.command.Ss7Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P1 sticky ownership / router without STP.
 */
public class Ss7DialogOwnershipAndStickyRouterTest {

    private ClusterManager manager;
    private Ss7DialogClusterCaches caches;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("p1-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        caches = Ss7DialogClusterCaches.ensureCaches(manager);
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    public void trackerWriteThroughPutUpdateRemove() {
        Ss7DialogOwnershipTracker tracker = new Ss7DialogOwnershipTracker(
                manager.getNodeId(), "ra-jss7", 1, 8, caches);
        tracker.onDialogOpened("42", 42L, new byte[] {1, 2}, 2, 6, "Active", "corr");
        assertTrue(tracker.lookupOwner("42").isPresent());
        assertEquals(manager.getNodeId(), tracker.lookupOwner("42").get().ownerNodeId());
        assertTrue(caches.getOwner("42") != null);
        assertTrue(caches.metaCache().get("42") != null);

        tracker.onDialogTouched("42", "Active", new byte[] {9}, 2, 6);
        assertEquals(9, caches.metaCache().get("42").remoteOtid()[0]);

        tracker.onDialogClosed("42");
        assertFalse(tracker.lookupOwner("42").isPresent());
        assertTrue(caches.getOwner("42") == null);
        assertTrue(caches.metaCache().get("42") == null);
    }

    @Test
    public void localOnlyTrackerWorksWithoutClusterCaches() {
        Ss7DialogOwnershipTracker tracker = Ss7DialogOwnershipTracker.localOnly("n1", "ra", 1, 8);
        assertFalse(tracker.isClustered());
        tracker.onDialogOpened("7", 7L, null, 0, 0, "Active", null);
        assertTrue(tracker.lookupOwner("7").isPresent());
        tracker.onDialogClosed("7");
        assertFalse(tracker.lookupOwner("7").isPresent());
    }

    @Test
    public void stickyRouterLocalOwnerSendsWhenReady() {
        Ss7DialogOwnershipTracker tracker = new Ss7DialogOwnershipTracker(
                manager.getNodeId(), "ra-jss7", 1, 8, caches);
        tracker.onDialogOpened("100", 100L, null, 0, 0, "Active", null);
        StickyRaCommandRouter router = new StickyRaCommandRouter(tracker);
        Ss7Command.TcapContinue cont = new Ss7Command.TcapContinue(
                "100", Ss7Address.of("1", 8), List.of(), 0);
        StickyRaCommandRouter.Decision d = router.decide(cont, true);
        assertEquals(StickyRaCommandRouter.Action.SEND_LOCAL, d.action());
    }

    @Test
    public void stickyRouterRejectsWhenNotReady() {
        Ss7DialogOwnershipTracker tracker = new Ss7DialogOwnershipTracker(
                manager.getNodeId(), "ra-jss7", 1, 8, caches);
        tracker.onDialogOpened("100", 100L, null, 0, 0, "Active", null);
        StickyRaCommandRouter router = new StickyRaCommandRouter(tracker);
        Ss7Command.TcapContinue cont = new Ss7Command.TcapContinue(
                "100", Ss7Address.of("1", 8), List.of(), 0);
        StickyRaCommandRouter.Decision d = router.decide(cont, false);
        assertEquals(StickyRaCommandRouter.Action.REJECT, d.action());
        assertTrue(d.reason().contains("isM3uaRouteReady"));
    }

    @Test
    public void stickyRouterForwardsRemoteOwner() {
        Ss7DialogOwnershipTracker tracker = new Ss7DialogOwnershipTracker(
                manager.getNodeId(), "ra-jss7", 1, 8, caches);
        RaDialogOwner remote = new RaDialogOwner("200", "other-node", "ra-jss7", 0L, System.currentTimeMillis());
        caches.putOwner(remote);
        StickyRaCommandRouter router = new StickyRaCommandRouter(tracker);
        Ss7Command.TcapContinue cont = new Ss7Command.TcapContinue(
                "200", Ss7Address.of("1", 8), List.of(), 0);
        StickyRaCommandRouter.Decision d = router.decide(cont, true);
        assertEquals(StickyRaCommandRouter.Action.FORWARD_REMOTE, d.action());
        assertEquals("other-node", d.owner().ownerNodeId());
    }

    @Test
    public void stickyRouterRejectsContinueWithoutOwner() {
        Ss7DialogOwnershipTracker tracker = Ss7DialogOwnershipTracker.localOnly("n1", "ra", 1, 8);
        StickyRaCommandRouter router = new StickyRaCommandRouter(tracker);
        Ss7Command.TcapContinue cont = new Ss7Command.TcapContinue(
                "missing", Ss7Address.of("1", 8), List.of(), 0);
        StickyRaCommandRouter.Decision d = router.decide(cont, true);
        assertEquals(StickyRaCommandRouter.Action.REJECT, d.action());
    }

    @Test
    public void stickyRouterAllowsDialogCreatingWithoutOwnerWhenReady() {
        Ss7DialogOwnershipTracker tracker = Ss7DialogOwnershipTracker.localOnly("n1", "ra", 1, 8);
        StickyRaCommandRouter router = new StickyRaCommandRouter(tracker);
        Ss7Command.TcapBegin begin = new Ss7Command.TcapBegin(
                "new-1", Ss7Address.of("2", 8), Ss7Address.of("1", 8), 1, List.of(), 0);
        StickyRaCommandRouter.Decision d = router.decide(begin, true);
        assertEquals(StickyRaCommandRouter.Action.SEND_LOCAL, d.action());
    }

    @Test
    public void ownershipCasBumpsGenerationViaCaches() {
        RaDialogOwner first = new RaDialogOwner("dlg", "node-a", "ra", 0L, 1L);
        assertTrue(caches.tryPutOwnerIfAbsent(first));
        assertTrue(caches.tryClaimOwnership(first, "node-b", "ra", 2L));
        assertEquals(1L, caches.getOwner("dlg").generation());
        assertFalse(caches.tryClaimOwnership(first, "node-c", "ra", 3L));
    }
}
