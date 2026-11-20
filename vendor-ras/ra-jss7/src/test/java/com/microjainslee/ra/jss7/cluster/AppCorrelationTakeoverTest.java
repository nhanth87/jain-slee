/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.TcapDialogSnapshotPayload;
import com.microjainslee.core.MicroSleeConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TcapDialogSnapshot;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.Dialog;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.TRPseudoState;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pending-issues #3 — the TCAP snapshot carries the app correlation id and a
 * takeover re-registers it plus rebuilds the MAP dialog wrapper, so multi-step
 * flows (ATI / sendRoamingNumber, USSD menus) keep publishing on the app id.
 * Single-op flows (no correlation) keep working exactly as before.
 */
public class AppCorrelationTakeoverTest {

    private ClusterManager manager;
    private Ss7DialogClusterCaches caches;
    private ParameterFactory parameterFactory;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("corr-" + UUID.randomUUID().toString().substring(0, 8))
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
    public void toPayload_carries_app_correlation_and_null_stays_null() {
        TcapDialogSnapshot live = liveSnapshot(101L);
        TcapDialogSnapshotPayload withCorr =
                Jss7TcapDialogFailoverPort.toPayload("101", live, "gmlc-uuid-1");
        assertEquals("gmlc-uuid-1", withCorr.appCorrelationId());

        TcapDialogSnapshotPayload singleOp = Jss7TcapDialogFailoverPort.toPayload("101", live);
        assertNull("single-op flows carry no correlation", singleOp.appCorrelationId());
    }

    @Test
    public void export_stores_correlation_and_takeover_reregisters_it() throws Exception {
        Map<Long, TcapDialogSnapshot> nodeBDialogs = new ConcurrentHashMap<>();
        AtomicReference<TcapDialogSnapshot> nodeAStore = new AtomicReference<>(liveSnapshot(202L));

        TCAPProvider providerA = proxyProvider(
                otid -> otid == 202L ? nodeAStore.get() : null,
                snap -> {
                    throw new UnsupportedOperationException("A does not import");
                });
        TCAPProvider providerB = proxyProvider(
                otid -> nodeBDialogs.get(otid),
                snap -> {
                    nodeBDialogs.put(snap.getLocalOtid(), snap);
                    return proxyDialog(snap.getLocalOtid());
                });

        Ss7DialogOwnershipTracker trackerA =
                new Ss7DialogOwnershipTracker("node-a", "ra-jss7", 1, 8, caches);
        trackerA.onDialogOpened("gmlc-uuid-9", 202L, new byte[] {2, 0, 2}, 2, 8, "Active", null);
        Jss7TcapDialogFailoverPort portA = new Jss7TcapDialogFailoverPort(
                () -> providerA, () -> parameterFactory, trackerA, caches);
        assertTrue(portA.exportAndStore(202L, "gmlc-uuid-9").isPresent());

        TcapDialogSnapshotPayload stored = caches.getSnapshot("202");
        assertNotNull(stored);
        assertEquals("gmlc-uuid-9", stored.appCorrelationId());

        // Owner death: A loses the dialog; B takes over with MAP rehydrate.
        nodeAStore.set(null);
        AtomicInteger rehydrated = new AtomicInteger();
        MapDialogRehydrator rehydrator = new MapDialogRehydrator(() -> proxyMapProvider(rehydrated));
        Ss7DialogOwnershipTracker trackerB =
                new Ss7DialogOwnershipTracker("node-b", "ra-jss7", 1, 8, caches);
        Jss7TcapDialogFailoverPort portB = new Jss7TcapDialogFailoverPort(
                () -> providerB, () -> parameterFactory, trackerB, caches,
                new TcapFailoverMetrics(), rehydrator);
        AtomicReference<String> registeredCorr = new AtomicReference<>();
        AtomicReference<Long> registeredOtid = new AtomicReference<>();
        portB.setCorrelationRegistrar((corr, otid) -> {
            registeredCorr.set(corr);
            registeredOtid.set(otid);
        });

        assertTrue(portB.tryTakeover(202L));
        assertTrue(nodeBDialogs.containsKey(202L));
        assertEquals("gmlc-uuid-9", registeredCorr.get());
        assertEquals(Long.valueOf(202L), registeredOtid.get());
        assertEquals("MAP wrapper must be rebuilt for multi-step flows",
                1, rehydrated.get());
    }

    @Test
    public void single_op_import_does_not_touch_the_registrar() throws Exception {
        Map<Long, TcapDialogSnapshot> nodeBDialogs = new ConcurrentHashMap<>();
        TCAPProvider providerB = proxyProvider(
                otid -> nodeBDialogs.get(otid),
                snap -> {
                    nodeBDialogs.put(snap.getLocalOtid(), snap);
                    return proxyDialog(snap.getLocalOtid());
                });
        Ss7DialogOwnershipTracker trackerB =
                new Ss7DialogOwnershipTracker("node-b", "ra-jss7", 1, 8, caches);
        Jss7TcapDialogFailoverPort portB = new Jss7TcapDialogFailoverPort(
                () -> providerB, () -> parameterFactory, trackerB, caches,
                new TcapFailoverMetrics(),
                new MapDialogRehydrator(() -> proxyMapProvider(new AtomicInteger())));
        AtomicInteger registrarCalls = new AtomicInteger();
        portB.setCorrelationRegistrar((corr, otid) -> registrarCalls.incrementAndGet());

        // Legacy payload: no correlation, no invokes → plain import still works.
        caches.putSnapshot(Jss7TcapDialogFailoverPort.toPayload("303", liveSnapshot(303L)));
        assertTrue(portB.tryTakeover(303L));
        assertEquals(0, registrarCalls.get());
    }

    // ----- fakes -----

    private TcapDialogSnapshot liveSnapshot(long otid) {
        SccpAddress local = parameterFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 1, 8);
        SccpAddress remote = parameterFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 2, 8);
        return new TcapDialogSnapshot(
                otid, new byte[] {1, 2, 3, 4}, local, remote, TRPseudoState.Active,
                null, System.currentTimeMillis() + 60_000, 0, 8, 2, 1, false, new boolean[256]);
    }

    private static TCAPProvider proxyProvider(
            java.util.function.LongFunction<TcapDialogSnapshot> exportFn,
            java.util.function.Function<TcapDialogSnapshot, Dialog> importFn) {
        return (TCAPProvider) Proxy.newProxyInstance(
                TcapDialogSnapshot.class.getClassLoader(),
                new Class<?>[] {TCAPProvider.class},
                (proxy, method, args) -> {
                    if ("exportDialog".equals(method.getName())) {
                        return exportFn.apply((Long) args[0]);
                    }
                    if ("importDialog".equals(method.getName())) {
                        return importFn.apply((TcapDialogSnapshot) args[0]);
                    }
                    if ("toString".equals(method.getName())) {
                        return "ProxyTCAPProvider";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Dialog proxyDialog(long localOtid) {
        return (Dialog) Proxy.newProxyInstance(
                TcapDialogSnapshot.class.getClassLoader(),
                new Class<?>[] {Dialog.class},
                (proxy, method, args) -> {
                    if ("getLocalDialogId".equals(method.getName())) {
                        return localOtid;
                    }
                    if ("toString".equals(method.getName())) {
                        return "ProxyDialog(" + localOtid + ")";
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) {
                        return false;
                    }
                    if (ret == int.class || ret == long.class) {
                        return 0;
                    }
                    return null;
                });
    }

    /** MAP provider whose wrapper is missing → forces rehydrateDialogFromTcap. */
    private static MAPProvider proxyMapProvider(AtomicInteger rehydrated) {
        return (MAPProvider) Proxy.newProxyInstance(
                TcapDialogSnapshot.class.getClassLoader(),
                new Class<?>[] {MAPProvider.class},
                (proxy, method, args) -> {
                    if ("getMAPDialog".equals(method.getName())) {
                        return null;
                    }
                    if ("rehydrateDialogFromTcap".equals(method.getName())) {
                        rehydrated.incrementAndGet();
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "ProxyMAPProvider";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
