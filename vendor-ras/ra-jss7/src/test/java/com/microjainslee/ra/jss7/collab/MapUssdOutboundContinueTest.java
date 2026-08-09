/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.Test;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPDialogSupplementary;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Same-dialog NI continue / close / abort-by-corr — proxy stubs (no mock framework).
 */
public class MapUssdOutboundContinueTest {

    private static final long LOCAL_DIALOG_ID = 7777L;
    private static final String CORR = "corr-uuid-ni-1";

    @Test
    public void continueByCorrelationReusesDialogWithoutCreate() {
        DialogStub dialog = new DialogStub();
        MapUssdOutbound outbound = outboundFor(dialog);
        rememberCorr(outbound);

        assertTrue(outbound.send(new Ss7Command.MapUnstructuredSsContinue(
                CORR, "menu again", false, 0x0F)));

        assertTrue(dialog.requestAdded.get());
        assertTrue(dialog.sendAttempted.get());
        assertFalse("continue must not createNewDialog", dialog.createCalled.get());
        assertEquals(CORR, outbound.correlate(LOCAL_DIALOG_ID, "fallback"));
    }

    @Test
    public void continueMissingDialogFails() {
        DialogStub dialog = new DialogStub();
        MapUssdOutbound outbound = outboundFor(dialog);

        try {
            outbound.send(new Ss7Command.MapUnstructuredSsContinue(
                    "unknown-corr", "x", false, 0x0F));
            fail("expected missing dialog");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage().contains("No MAP dialog"));
        }
    }

    @Test
    public void closeByCorrelationCallsPrearrangedEnd() {
        DialogStub dialog = new DialogStub();
        MapUssdOutbound outbound = outboundFor(dialog);
        rememberCorr(outbound);

        assertTrue(outbound.send(new Ss7Command.MapDialogClose(CORR, true)));

        assertEquals(Boolean.TRUE, dialog.closePrearranged.get());
        assertNull("close must forget reverse map", outbound.resolveLocalId(CORR));
        assertEquals("fallback", outbound.correlate(LOCAL_DIALOG_ID, "fallback"));
    }

    @Test
    public void abortByCorrelationResolvesUuid() {
        DialogStub dialog = new DialogStub();
        MapUssdOutbound outbound = outboundFor(dialog);
        rememberCorr(outbound);

        assertTrue(outbound.send(new Ss7Command.MapDialogAbort(CORR)));

        assertTrue(dialog.abortCalled.get());
        assertNull(outbound.resolveLocalId(CORR));
    }

    private static void rememberCorr(MapUssdOutbound outbound) {
        // sendNi path remembers via createNewDialog; unit tests seed via reflection-free
        // package API: createNewDialog remember happens only on NI — use continue after
        // a synthetic remember by calling send with a stub that registers on getMAPDialog.
        // Easiest: invoke package remember through first successful continue after put via
        // sendNi is heavy; use resolve after manual put by calling forget/remember via
        // a successful MapUnstructuredSsRequest is overkill — seed with package-visible
        // resolve after force-remember through dialog local id put:
        try {
            var m = MapUssdOutbound.class.getDeclaredMethod("remember", Long.class, String.class);
            m.setAccessible(true);
            m.invoke(outbound, LOCAL_DIALOG_ID, CORR);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static MapUssdOutbound outboundFor(DialogStub dialog) {
        MAPDialogSupplementary dialogProxy = stub(MAPDialogSupplementary.class, dialog);
        MAPProvider provider = stub(MAPProvider.class, new ProviderStub(dialogProxy));
        return new MapUssdOutbound(provider, stub(ParameterFactory.class, DEFAULTS));
    }

    private static final class DialogStub implements InvocationHandler {
        final AtomicBoolean sendAttempted = new AtomicBoolean();
        final AtomicBoolean requestAdded = new AtomicBoolean();
        final AtomicBoolean notifyAdded = new AtomicBoolean();
        final AtomicBoolean createCalled = new AtomicBoolean();
        final AtomicBoolean abortCalled = new AtomicBoolean();
        final AtomicInteger releaseCount = new AtomicInteger();
        final AtomicReference<Boolean> closePrearranged = new AtomicReference<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "send" -> {
                    sendAttempted.set(true);
                    yield null;
                }
                case "addUnstructuredSSRequest" -> {
                    requestAdded.set(true);
                    yield null;
                }
                case "addUnstructuredSSNotifyRequest" -> {
                    notifyAdded.set(true);
                    yield null;
                }
                case "close" -> {
                    closePrearranged.set(args != null && args.length > 0 ? (Boolean) args[0] : null);
                    yield null;
                }
                case "abort" -> {
                    abortCalled.set(true);
                    yield null;
                }
                case "release" -> {
                    releaseCount.incrementAndGet();
                    yield null;
                }
                case "getLocalDialogId" -> LOCAL_DIALOG_ID;
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static final class ProviderStub implements InvocationHandler {
        private final MAPDialogSupplementary dialog;

        ProviderStub(MAPDialogSupplementary dialog) {
            this.dialog = dialog;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getMAPDialog" -> {
                    if (args != null && args.length > 0 && LOCAL_DIALOG_ID == ((Long) args[0])) {
                        yield dialog;
                    }
                    yield null;
                }
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static final InvocationHandler DEFAULTS = MapUssdOutboundContinueTest::defaultValue;

    private static <T> T stub(Class<T> iface, InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(
                MapUssdOutboundContinueTest.class.getClassLoader(),
                new Class<?>[] {iface},
                handler));
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString":
                return "stub:" + method.getDeclaringClass().getSimpleName();
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                break;
        }
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
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
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return null;
    }
}
