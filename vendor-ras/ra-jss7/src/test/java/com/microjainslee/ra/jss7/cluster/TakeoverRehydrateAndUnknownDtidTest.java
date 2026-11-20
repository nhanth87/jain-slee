package com.microjainslee.ra.jss7.cluster;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.cluster.TcapDialogSnapshotPayload;
import com.microjainslee.core.MicroSleeConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TcapDialogSnapshot;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.Dialog;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.TRPseudoState;

/**
 * Gap (3): MAP multi-step rehydrate after takeover (ATI, sendRoamingNumber, USSD
 * menu continue), pending-invoke abort, unknown DTID -> UnrecognizedTxID
 * (dropped+counted, never crashes).
 */
public class TakeoverRehydrateAndUnknownDtidTest {

    private ClusterManager manager;
    private Ss7DialogClusterCaches caches;
    private ParameterFactory parameterFactory;

    @Before
    public void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(false)
                .nodeId("rehyd-" + UUID.randomUUID().toString().substring(0, 8))
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
    public void unknownDtidResolveIsDroppedCountedAndNeverCrashes() {
        TcapFailoverMetrics metrics = new TcapFailoverMetrics();
        Ss7DialogOwnershipTracker tracker = Ss7DialogOwnershipTracker.localOnly(
                manager.getNodeId(), "ra-jss7", 1, 8);
        Jss7TcapDialogFailoverPort port = new Jss7TcapDialogFailoverPort(
                () -> proxyProvider(otid -> null, snap -> null),
                () -> parameterFactory, tracker, caches, metrics);

        // No snapshot stored for this DTID -> treated as UnrecognizedTxID path:
        // resolve returns null, counts a continue-miss/resolve-fail, never crashes.
        assertNull(port.resolve(999_001L));
        assertEquals(1, metrics.continueMissCount());
        assertEquals(1, metrics.continueResolveFailCount());
        assertEquals(0, metrics.importOkCount());

        assertNull(port.resolve(999_002L));
        assertEquals(2, metrics.continueMissCount());
        assertEquals(2, metrics.continueResolveFailCount());
    }

    @Test
    public void pendingInvokeAbort_refusedOnResolveAndOnImport() {
        boolean[] taken = new boolean[256];
        taken[1] = true;
        TcapDialogSnapshotPayload payload = new TcapDialogSnapshotPayload(
                "711", 711L, new byte[]{7, 1, 1},
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(1, 8),
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(2, 8),
                "Active", null, 0L, 0, 8, 2, 1, false, taken, System.currentTimeMillis());
        assertTrue(payload.hasUnrestorablePendingInvokes());
        caches.putSnapshot(payload);

        Map<Long, TcapDialogSnapshot> imported = new ConcurrentHashMap<>();
        TcapFailoverMetrics metrics = new TcapFailoverMetrics();
        Ss7DialogOwnershipTracker tracker = new Ss7DialogOwnershipTracker(
                manager.getNodeId(), "ra-jss7", 1, 8, caches);
        Jss7TcapDialogFailoverPort port = new Jss7TcapDialogFailoverPort(
                () -> proxyProvider(otid -> imported.get(otid), snap -> {
                    imported.put(snap.getLocalOtid(), snap);
                    return proxyDialog(snap.getLocalOtid());
                }),
                () -> parameterFactory, tracker, caches, metrics);

        // CONTINUE-miss resume refused for unrestorable pending invokes.
        assertNull(port.resolve(711L));
        assertEquals(1, metrics.pendingInvokeAbortCount());
        assertEquals(1, metrics.continueResolveFailCount());

        // Explicit import follows recreate+abort policy and does not leave a
        // resumable snapshot behind (peer must start a new BEGIN).
        caches.putSnapshot(payload);
        assertFalse(port.importPayload(payload));
        assertTrue(imported.containsKey(711L));
        assertNull("snapshot dropped after pending-invoke abort", caches.getSnapshot("711"));
        assertEquals(2, metrics.pendingInvokeAbortCount());
    }

    @Test
    public void atiMultiStepTakeoverRehydratesAndReregistersCorrelation() throws Exception {
        assertMultiStepFlowRehydrated("ati-corr-1", 501L);
    }

    @Test
    public void sendRoamingNumberMultiStepTakeoverRehydratesAndReregistersCorrelation() throws Exception {
        assertMultiStepFlowRehydrated("srn-corr-2", 502L);
    }

    @Test
    public void ussdMenuContinueMultiStepTakeoverRehydratesAndReregistersCorrelation() throws Exception {
        assertMultiStepFlowRehydrated("ussd-menu-corr-3", 503L);
    }

    private void assertMultiStepFlowRehydrated(String correlationId, long otid) throws Exception {
        AtomicReference<TcapDialogSnapshot> nodeAStore = new AtomicReference<>(liveSnapshot(otid));
        Map<Long, TcapDialogSnapshot> nodeBDialogs = new ConcurrentHashMap<>();

        TCAPProvider providerA = proxyProvider(
                id -> id == otid ? nodeAStore.get() : null,
                snap -> { throw new UnsupportedOperationException("A does not import"); });
        TCAPProvider providerB = proxyProvider(
                nodeBDialogs::get,
                snap -> {
                    nodeBDialogs.put(snap.getLocalOtid(), snap);
                    return proxyDialog(snap.getLocalOtid());
                });

        Ss7DialogOwnershipTracker trackerA =
                new Ss7DialogOwnershipTracker("node-a", "ra-jss7", 1, 8, caches);
        trackerA.onDialogOpened(correlationId, otid, new byte[]{1, 2, 3, 4}, 2, 8, "Active", null);
        Jss7TcapDialogFailoverPort portA = new Jss7TcapDialogFailoverPort(
                () -> providerA, () -> parameterFactory, trackerA, caches);
        assertTrue(portA.exportAndStore(otid, correlationId).isPresent());
        assertEquals(correlationId, caches.getSnapshot(String.valueOf(otid)).appCorrelationId());

        nodeAStore.set(null); // owner dead
        AtomicInteger rehydrated = new AtomicInteger();
        MapDialogRehydrator rehydrator = new MapDialogRehydrator(() -> proxyMapProvider(rehydrated));
        Ss7DialogOwnershipTracker trackerB =
                new Ss7DialogOwnershipTracker("node-b", "ra-jss7", 1, 8, caches);
        Jss7TcapDialogFailoverPort portB = new Jss7TcapDialogFailoverPort(
                () -> providerB, () -> parameterFactory, trackerB, caches,
                new TcapFailoverMetrics(), rehydrator);
        AtomicReference<String> registeredCorr = new AtomicReference<>();
        AtomicReference<Long> registeredOtid = new AtomicReference<>();
        portB.setCorrelationRegistrar((corr, id) -> {
            registeredCorr.set(corr);
            registeredOtid.set(id);
        });

        assertTrue("multi-step flow must be importable after takeover", portB.tryTakeover(otid));
        assertTrue(nodeBDialogs.containsKey(otid));
        assertEquals(correlationId, registeredCorr.get());
        assertEquals(Long.valueOf(otid), registeredOtid.get());
        assertEquals(1, rehydrated.get());
    }

    private TcapDialogSnapshot liveSnapshot(long otid) {
        SccpAddress local = parameterFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 1, 8);
        SccpAddress remote = parameterFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 2, 8);
        return new TcapDialogSnapshot(
                otid, new byte[]{1, 2, 3, 4}, local, remote, TRPseudoState.Active,
                null, System.currentTimeMillis() + 60_000, 0, 8, 2, 1, false, new boolean[256]);
    }

    private static TCAPProvider proxyProvider(
            java.util.function.LongFunction<TcapDialogSnapshot> exportFn,
            java.util.function.Function<TcapDialogSnapshot, Dialog> importFn) {
        return (TCAPProvider) Proxy.newProxyInstance(
                TakeoverRehydrateAndUnknownDtidTest.class.getClassLoader(),
                new Class<?>[]{TCAPProvider.class},
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
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    return null;
                });
    }

    private static Dialog proxyDialog(long localOtid) {
        return (Dialog) Proxy.newProxyInstance(
                TakeoverRehydrateAndUnknownDtidTest.class.getClassLoader(),
                new Class<?>[]{Dialog.class},
                (proxy, method, args) -> {
                    if ("getLocalDialogId".equals(method.getName())) return localOtid;
                    if ("toString".equals(method.getName())) return "ProxyDialog(" + localOtid + ")";
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    return null;
                });
    }

    private static MAPProvider proxyMapProvider(AtomicInteger rehydrated) {
        return (MAPProvider) Proxy.newProxyInstance(
                TakeoverRehydrateAndUnknownDtidTest.class.getClassLoader(),
                new Class<?>[]{MAPProvider.class},
                (proxy, method, args) -> {
                    if ("getMAPDialog".equals(method.getName())) return null;
                    if ("rehydrateDialogFromTcap".equals(method.getName())) {
                        rehydrated.incrementAndGet();
                        return null;
                    }
                    if ("toString".equals(method.getName())) return "ProxyMAPProvider";
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    return null;
                });
    }
}
