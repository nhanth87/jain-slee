/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.cluster.TcapDialogSnapshotPayload;
import com.microjainslee.cluster.TcapDialogSnapshotPayload.PortableSccpAddress;
import com.microjainslee.core.MicroSleeConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TcapDialogSnapshot;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.Dialog;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.TRPseudoState;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ADR 0007 D3 — the takeover guard (dialog lease) must refuse a takeover while
 * the owner still holds the lease, and allow it once the lease is won. Without
 * a guard the port imports unconditionally (single-node / legacy behaviour).
 */
public class TakeoverGuardTest {

    private ClusterManager manager;
    private Ss7DialogClusterCaches caches;
    private ParameterFactory parameterFactory;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("guard-" + UUID.randomUUID().toString().substring(0, 8))
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
    public void guardRefusesTakeoverAndResolve() {
        caches.putSnapshot(samplePayload(321L));
        AtomicBoolean imported = new AtomicBoolean();
        Jss7TcapDialogFailoverPort port = port(imported);
        port.setTakeoverGuard(otid -> false);

        assertFalse("lease owner alive -> takeover must be refused", port.tryTakeover(321L));
        assertFalse("no import may happen when the guard refuses", imported.get());
        assertNull("CONTINUE-miss resolve must also be refused", port.resolve(321L));
    }

    @Test
    public void guardAllowsTakeoverAndResolve() {
        caches.putSnapshot(samplePayload(322L));
        AtomicBoolean imported = new AtomicBoolean();
        Jss7TcapDialogFailoverPort port = port(imported);
        port.setTakeoverGuard(otid -> true);

        assertTrue(port.tryTakeover(322L));
        assertTrue("guard allowed -> the dialog must be imported", imported.get());
        assertNotNull(port.resolve(322L));
    }

    @Test
    public void nullGuardKeepsLegacyUnconditionalTakeover() {
        caches.putSnapshot(samplePayload(323L));
        AtomicBoolean imported = new AtomicBoolean();
        Jss7TcapDialogFailoverPort port = port(imported);
        assertTrue(port.tryTakeover(323L));
        assertTrue(imported.get());
    }

    // ----- fakes -----

    private Jss7TcapDialogFailoverPort port(AtomicBoolean imported) {
        Ss7DialogOwnershipTracker tracker = new Ss7DialogOwnershipTracker(
                "node-guard", "ra-jss7", 1, 8, caches);
        return new Jss7TcapDialogFailoverPort(
                () -> proxyProvider(otid -> null, snap -> {
                    imported.set(true);
                    return proxyDialog(snap.getLocalOtid());
                }),
                () -> parameterFactory,
                tracker,
                caches);
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

    private static TCAPProvider proxyProvider(
            java.util.function.LongFunction<TcapDialogSnapshot> exportFn,
            java.util.function.Function<TcapDialogSnapshot, Dialog> importFn) {
        return (TCAPProvider) Proxy.newProxyInstance(
                TCAPProvider.class.getClassLoader(),
                new Class<?>[] {TCAPProvider.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("exportDialog".equals(name)) {
                        return exportFn.apply((Long) args[0]);
                    }
                    if ("importDialog".equals(name)) {
                        return importFn.apply((TcapDialogSnapshot) args[0]);
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
                    return defaultReturn(method);
                });
    }

    private static Dialog proxyDialog(long localOtid) {
        return (Dialog) Proxy.newProxyInstance(
                Dialog.class.getClassLoader(),
                new Class<?>[] {Dialog.class},
                (proxy, method, args) -> {
                    if ("getLocalDialogId".equals(method.getName())) {
                        return localOtid;
                    }
                    if ("toString".equals(method.getName())) {
                        return "ProxyDialog(" + localOtid + ")";
                    }
                    return defaultReturn(method);
                });
    }

    private static Object defaultReturn(Method method) {
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
        return null;
    }
}
