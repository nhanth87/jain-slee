/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.MarshallingAllowList;
import com.microjainslee.cluster.RaDialogOwner;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.cluster.TcapDialogSnapshotPayload;
import com.microjainslee.cluster.TcapDialogSnapshotPayload.PortableSccpAddress;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.ra.jss7.command.Ss7Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.TCAPException;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TcapDialogSnapshot;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.Dialog;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.TRPseudoState;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P2 RA-wired failover without STP: export → ISPN payload → import / tryTakeover.
 */
public class Jss7TcapDialogFailoverPortTest {

    private ClusterManager manager;
    private Ss7DialogClusterCaches caches;
    private ParameterFactory parameterFactory;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("p2-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        caches = Ss7DialogClusterCaches.ensureCaches(manager);
        parameterFactory = new ParameterFactoryImpl();
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    public void snapshotPayloadIsMarshallableAndCachesRoundTrip() {
        TcapDialogSnapshotPayload payload = samplePayload(55L);
        MarshallingAllowList.assertMarshallable("snapshot", payload);
        caches.putSnapshot(payload);
        assertEquals(payload, caches.getSnapshot("55"));
        caches.removeMeta("55");
        assertTrue(caches.getSnapshot("55") == null);
    }

    @Test
    public void portableAddressRoundTripPreservesPcSsn() {
        SccpAddress local = parameterFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 1, 8);
        SccpAddress remote = parameterFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 2, 6);
        TcapDialogSnapshot snap = new TcapDialogSnapshot(
                42L, new byte[] {1, 2, 3, 4}, local, remote, TRPseudoState.Active,
                new long[] {0, 4, 0, 0, 1, 0, 19, 2}, System.nanoTime() + 60_000_000_000L,
                0, 8, 2, 7, true, new boolean[256]);
        TcapDialogSnapshotPayload payload = Jss7TcapDialogFailoverPort.toPayload("42", snap);
        TcapDialogSnapshot restored = Jss7TcapDialogFailoverPort.toJss7Snapshot(payload, parameterFactory);
        assertEquals(42L, restored.getLocalOtid());
        assertEquals(TRPseudoState.Active, restored.getState());
        assertEquals(1, restored.getLocalAddress().getSignalingPointCode());
        assertEquals(8, restored.getLocalAddress().getSubsystemNumber());
        assertEquals(2, restored.getRemoteAddress().getSignalingPointCode());
        assertEquals(4, restored.getRemoteOtid()[3]);
    }

    @Test
    public void exportStoreThenImportOnSecondProviderViaTryTakeover() throws Exception {
        AtomicReference<TcapDialogSnapshot> providerAStore = new AtomicReference<>();
        Map<Long, TcapDialogSnapshot> providerBDialogs = new ConcurrentHashMap<>();

        SccpAddress local = parameterFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 1, 8);
        SccpAddress remote = parameterFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 2, 8);
        TcapDialogSnapshot live = new TcapDialogSnapshot(
                99L, new byte[] {9, 9, 9, 9}, local, remote, TRPseudoState.Active,
                null, System.nanoTime() + 30_000_000_000L, 0, 8, 2, 3, false, new boolean[256]);
        providerAStore.set(live);

        TCAPProvider providerA = proxyProvider(otid -> otid == 99L ? providerAStore.get() : null,
                snap -> {
                    throw new UnsupportedOperationException("A does not import");
                });
        TCAPProvider providerB = proxyProvider(
                otid -> providerBDialogs.containsKey(otid)
                        ? providerBDialogs.get(otid)
                        : null,
                snap -> {
                    providerBDialogs.put(snap.getLocalOtid(), snap);
                    return null;
                });

        Ss7DialogOwnershipTracker trackerA = new Ss7DialogOwnershipTracker(
                "node-a", "ra-jss7", 1, 8, caches);
        trackerA.onDialogOpened("99", 99L, new byte[] {9, 9, 9, 9}, 2, 8, "Active", null);

        Jss7TcapDialogFailoverPort portA = new Jss7TcapDialogFailoverPort(
                () -> providerA, () -> parameterFactory, trackerA, caches);
        assertTrue(portA.exportAndStore(99L).isPresent());
        assertNotNull(caches.getSnapshot("99"));

        // Simulate owner death: detach A dialog.
        providerAStore.set(null);

        Ss7DialogOwnershipTracker trackerB = new Ss7DialogOwnershipTracker(
                "node-b", "ra-jss7", 1, 8, caches);
        Jss7TcapDialogFailoverPort portB = new Jss7TcapDialogFailoverPort(
                () -> providerB, () -> parameterFactory, trackerB, caches);

        assertTrue(portB.tryTakeover(99L));
        assertTrue(providerBDialogs.containsKey(99L));
        assertEquals(TRPseudoState.Active, providerBDialogs.get(99L).getState());

        RaDialogOwner owner = caches.getOwner("99");
        assertNotNull(owner);
        // CAS may bump generation when previous owner was node-a
        assertEquals("node-b", trackerB.lookupOwner("99").get().ownerNodeId());
    }

    @Test
    public void missingDialogResolverReturnsCachedSnapshot() {
        TcapDialogSnapshotPayload payload = samplePayload(77L);
        caches.putSnapshot(payload);
        Ss7DialogOwnershipTracker tracker = new Ss7DialogOwnershipTracker(
                manager.getNodeId(), "ra-jss7", 1, 8, caches);
        Jss7TcapDialogFailoverPort port = new Jss7TcapDialogFailoverPort(
                () -> proxyProvider(otid -> null, snap -> null),
                () -> parameterFactory,
                tracker,
                caches);
        TcapDialogSnapshot resolved = port.resolve(77L);
        assertNotNull(resolved);
        assertEquals(77L, resolved.getLocalOtid());
        assertEquals(TRPseudoState.Active, resolved.getState());
    }

    @Test
    public void continueMissResolveClaimsOwnershipForStickySendLocal() {
        // Owner A published snapshot; A is "dead". Survivor B resolves CONTINUE-miss
        // and must claim ownership so sticky CONTINUE is SEND_LOCAL (not REJECT).
        TcapDialogSnapshotPayload payload = samplePayload(88L);
        caches.putSnapshot(payload);

        Ss7DialogOwnershipTracker trackerA = new Ss7DialogOwnershipTracker(
                "node-a", "ra-jss7", 1, 8, caches);
        trackerA.onDialogOpened("88", 88L, new byte[] {1, 2, 3, 4}, 2, 8, "Active", null);
        assertEquals("node-a", caches.getOwner("88").ownerNodeId());

        Ss7DialogOwnershipTracker trackerB = new Ss7DialogOwnershipTracker(
                "node-b", "ra-jss7", 1, 8, caches);
        Jss7TcapDialogFailoverPort portB = new Jss7TcapDialogFailoverPort(
                () -> proxyProvider(otid -> null, snap -> null),
                () -> parameterFactory,
                trackerB,
                caches);

        assertNotNull(portB.resolve(88L));
        assertEquals("node-b", trackerB.lookupOwner("88").get().ownerNodeId());

        StickyRaCommandRouter router = new StickyRaCommandRouter(trackerB);
        Ss7Command.TcapContinue continueCmd = new Ss7Command.TcapContinue(
                "88", null, java.util.List.of(), 0);
        StickyRaCommandRouter.Decision decision = router.decide(continueCmd, true);
        assertEquals(StickyRaCommandRouter.Action.SEND_LOCAL, decision.action());
    }

    @Test
    public void unsupportedPortRemainsNoOp() {
        TcapDialogFailoverPort port = TcapDialogFailoverPort.unsupported();
        assertFalse(port.exportAndStore(1L).isPresent());
        assertFalse(port.importPayload(samplePayload(1L)));
        assertFalse(port.tryTakeover(1L));
    }

    private static TcapDialogSnapshotPayload samplePayload(long otid) {
        return new TcapDialogSnapshotPayload(
                String.valueOf(otid),
                otid,
                new byte[] {1, 2, 3, 4},
                PortableSccpAddress.pcSsn(1, 8),
                PortableSccpAddress.pcSsn(2, 8),
                "Active",
                null,
                System.nanoTime() + 10_000_000_000L,
                0,
                8,
                2,
                1,
                false,
                new boolean[256],
                System.currentTimeMillis());
    }

    @FunctionalInterface
    private interface ExportFn {
        TcapDialogSnapshot export(long otid);
    }

    @FunctionalInterface
    private interface ImportFn {
        Dialog importDialog(TcapDialogSnapshot snap) throws TCAPException;
    }

    private static TCAPProvider proxyProvider(ExportFn exportFn, ImportFn importFn) {
        return (TCAPProvider) Proxy.newProxyInstance(
                TCAPProvider.class.getClassLoader(),
                new Class<?>[] {TCAPProvider.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("exportDialog".equals(name)) {
                        return exportFn.export((Long) args[0]);
                    }
                    if ("importDialog".equals(name)) {
                        return importFn.importDialog((TcapDialogSnapshot) args[0]);
                    }
                    if ("toString".equals(name)) {
                        return "ProxyTCAPProvider";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == void.class) {
                        return null;
                    }
                    return null;
                });
    }
}
