/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.cluster.StickyRaCommandRouter;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.Test;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.service.callhandling.MAPDialogCallHandling;
import org.restcomm.protocols.ss7.map.api.service.callhandling.MAPServiceCallHandling;
import org.restcomm.protocols.ss7.map.api.service.lsm.MAPDialogLsm;
import org.restcomm.protocols.ss7.map.api.service.lsm.MAPServiceLsm;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobility;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MapGmlcOutboundTest {

    private static final long LOCAL_ID = 8801L;
    private static final Ss7Address HLR = Ss7Address.of("251900000001", 6);
    private static final Ss7Address GMLC = Ss7Address.of("251900000002", 145);

    @Test
    public void commandsAreSerializableAndRouteAsDialogCreating() {
        List<Ss7Command> creating = List.of(
                ati(),
                sri(),
                psi(),
                sriLcs(),
                psl());

        for (Ss7Command command : creating) {
            assertTrue(command instanceof Serializable);
            assertTrue(command.getClass().getSimpleName(),
                    StickyRaCommandRouter.isDialogCreating(command));
        }
        assertFalse(StickyRaCommandRouter.isDialogCreating(slrResponse()));
    }

    @Test
    public void selectsProtocolApplicationContexts() {
        assertContext(ati(), MAPApplicationContextName.anyTimeEnquiryContext);
        assertContext(sri(), MAPApplicationContextName.locationInfoRetrievalContext);
        assertContext(psi(), MAPApplicationContextName.subscriberInfoEnquiryContext);
        assertContext(sriLcs(), MAPApplicationContextName.locationSvcGatewayContext);
        assertContext(psl(), MAPApplicationContextName.locationSvcEnquiryContext);
    }

    @Test
    public void sriV2UsesLocationInfoRetrievalContextVersion2() {
        MAPApplicationContext context = MapGmlcOutbound.applicationContextFor(sriV2());
        assertSame(MAPApplicationContextName.locationInfoRetrievalContext,
                context.getApplicationContextName());
        assertEquals(2, context.getApplicationContextVersion().getVersion());
    }

    @Test
    public void correlatesSentDialogAndForgetsIt() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);

        assertTrue(outbound.send(ati()));
        assertEquals(1, dialog.sendCount.get());
        assertEquals(0, dialog.releaseCount.get());
        assertEquals("corr-ati", outbound.correlate(LOCAL_ID, "fallback"));

        outbound.forget(LOCAL_ID);
        assertEquals("fallback", outbound.correlate(LOCAL_ID, "fallback"));
    }

    @Test
    public void failedSendReleasesExactlyOnceAndCleansCorrelation() {
        DialogStub dialog = new DialogStub(new MAPException("route down"));
        MapGmlcOutbound outbound = outbound(dialog);

        try {
            outbound.send(ati());
            fail("expected failed MAP send");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof MAPException);
        }

        assertEquals(1, dialog.sendCount.get());
        assertEquals(1, dialog.releaseCount.get());
        assertEquals("fallback", outbound.correlate(LOCAL_ID, "fallback"));
    }

    @Test
    public void invalidLcsIdentityFailsClosedAndCleansCreatedDialog() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);
        Ss7Command.MapSendRoutingInfoForLcs invalid =
                new Ss7Command.MapSendRoutingInfoForLcs(
                        "corr-invalid", HLR, GMLC, "251900000002",
                        "636010000000001", "251911000001", 0, null, -1);

        try {
            outbound.send(invalid);
            fail("expected ambiguous identity rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }

        assertEquals(0, dialog.sendCount.get());
        assertEquals(1, dialog.releaseCount.get());
        assertEquals("fallback", outbound.correlate(LOCAL_ID, "fallback"));
    }

    @Test
    public void slrResponseAddsResultAndEndsExistingDialogExactlyOnce() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);

        assertTrue(outbound.send(slrResponse()));

        assertEquals(1, dialog.slrResponseCount.get());
        assertEquals(1, dialog.closeCount.get());
        assertEquals(0, dialog.sendCount.get());
        assertEquals(0, dialog.releaseCount.get());
    }

    @Test
    public void slrCloseFailureReleasesExistingDialogExactlyOnce() {
        DialogStub dialog = new DialogStub(null, new MAPException("close failed"));
        MapGmlcOutbound outbound = outbound(dialog);

        try {
            outbound.send(slrResponse());
            fail("expected failed MAP close");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof MAPException);
        }

        assertEquals(1, dialog.slrResponseCount.get());
        assertEquals(1, dialog.closeCount.get());
        assertEquals(1, dialog.releaseCount.get());
    }

    private static void assertContext(Ss7Command command, MAPApplicationContextName expected) {
        MAPApplicationContext context = MapGmlcOutbound.applicationContextFor(command);
        assertSame(expected, context.getApplicationContextName());
        assertEquals(3, context.getApplicationContextVersion().getVersion());
    }

    private static MapGmlcOutbound outbound(DialogStub dialog) {
        MAPDialog dialogProxy = combinedDialog(dialog);
        MAPProvider provider = proxy(MAPProvider.class, new ProviderStub(dialogProxy));
        ParameterFactory sccp = proxy(ParameterFactory.class, MapGmlcOutboundTest::defaultValue);
        return new MapGmlcOutbound(provider, sccp);
    }

    private static MAPDialog combinedDialog(DialogStub handler) {
        return (MAPDialog) Proxy.newProxyInstance(
                MapGmlcOutboundTest.class.getClassLoader(),
                new Class<?>[] {
                        MAPDialogMobility.class,
                        MAPDialogCallHandling.class,
                        MAPDialogLsm.class
                },
                handler);
    }

    private static Ss7Command.MapAtiRequest ati() {
        return new Ss7Command.MapAtiRequest(
                "corr-ati", HLR, GMLC, "251911000001", "251900000002",
                "csDomain", true, true, true, true, true, true, true,
                0, null, -1);
    }

    private static Ss7Command.MapSendRoutingInformation sri() {
        return new Ss7Command.MapSendRoutingInformation(
                "corr-sri", HLR, GMLC, "251911000001", 0, null, -1);
    }

    private static Ss7Command.MapSendRoutingInformation sriV2() {
        return new Ss7Command.MapSendRoutingInformation(
                "corr-sri-v2", HLR, GMLC, "251911000001", 0, null, -1, 2);
    }

    private static Ss7Command.MapProvideSubscriberInfo psi() {
        return new Ss7Command.MapProvideSubscriberInfo(
                "corr-psi", Ss7Address.of("251900000003", 7), GMLC,
                "636010000000001", null, "csDomain",
                true, true, true, true, true, true, true,
                0, null, -1);
    }

    private static Ss7Command.MapSendRoutingInfoForLcs sriLcs() {
        return new Ss7Command.MapSendRoutingInfoForLcs(
                "corr-sri-lcs", HLR, GMLC, "251900000002",
                null, "251911000001", 0, null, -1);
    }

    private static Ss7Command.MapProvideSubscriberLocation psl() {
        return new Ss7Command.MapProvideSubscriberLocation(
                "corr-psl", Ss7Address.of("251900000003", 8), GMLC,
                "currentLocation", "251900000002", "plmnOperatorServices",
                false, "636010000000001", null, null, null,
                "normalPriority", 100, null, false,
                "delaytolerant", false, "bestEffort", 7, 1,
                0, null, -1);
    }

    private static Ss7Command.MapSubscriberLocationReportResponse slrResponse() {
        return new Ss7Command.MapSubscriberLocationReportResponse(
                Long.toString(LOCAL_ID), Ss7Address.of("251900000003", 8),
                41L, null, null, 7, 0);
    }

    private static final class DialogStub implements InvocationHandler {
        private final Throwable sendFailure;
        private final Throwable closeFailure;
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicInteger slrResponseCount = new AtomicInteger();

        private DialogStub(Throwable sendFailure) {
            this(sendFailure, null);
        }

        private DialogStub(Throwable sendFailure, Throwable closeFailure) {
            this.sendFailure = sendFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getLocalDialogId" -> LOCAL_ID;
                case "send" -> {
                    sendCount.incrementAndGet();
                    if (sendFailure != null) {
                        throw sendFailure;
                    }
                    yield null;
                }
                case "close" -> {
                    closeCount.incrementAndGet();
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                    yield null;
                }
                case "release" -> {
                    releaseCount.incrementAndGet();
                    yield null;
                }
                case "addSubscriberLocationReportResponse" -> {
                    slrResponseCount.incrementAndGet();
                    yield null;
                }
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static final class ProviderStub implements InvocationHandler {
        private final MAPDialog dialog;
        private final MAPServiceMobility mobility;
        private final MAPServiceCallHandling callHandling;
        private final MAPServiceLsm lsm;
        private final MAPParameterFactory parameters;

        private ProviderStub(MAPDialog dialog) {
            this.dialog = dialog;
            InvocationHandler service = (proxy, method, args) ->
                    "createNewDialog".equals(method.getName())
                            ? dialog
                            : defaultValue(proxy, method, args);
            mobility = proxy(MAPServiceMobility.class, service);
            callHandling = proxy(MAPServiceCallHandling.class, service);
            lsm = proxy(MAPServiceLsm.class, service);
            parameters = proxy(MAPParameterFactory.class, MapGmlcOutboundTest::defaultValue);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getMAPDialog" -> dialog;
                case "getMAPServiceMobility" -> mobility;
                case "getMAPServiceCallHandling" -> callHandling;
                case "getMAPServiceLsm" -> lsm;
                case "getMAPParameterFactory" -> parameters;
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                MapGmlcOutboundTest.class.getClassLoader(),
                new Class<?>[] {type},
                handler));
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "stub:" + method.getDeclaringClass().getSimpleName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultForType(method.getReturnType());
        };
    }

    private static Object defaultForType(Class<?> type) {
        if (type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type.isInterface()) {
            return Proxy.newProxyInstance(
                    MapGmlcOutboundTest.class.getClassLoader(),
                    new Class<?>[] {type},
                    MapGmlcOutboundTest::defaultValue);
        }
        return null;
    }
}
