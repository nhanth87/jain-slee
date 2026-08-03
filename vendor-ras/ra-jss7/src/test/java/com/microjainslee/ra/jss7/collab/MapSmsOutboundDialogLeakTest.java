/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.Test;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.MAPSmsTpduParameterFactory;
import org.restcomm.protocols.ss7.map.api.service.sms.MAPDialogSms;
import org.restcomm.protocols.ss7.map.api.service.sms.MAPServiceSms;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A MAP dialog whose request never reaches the wire must be released, otherwise it holds a
 * jSS7 {@code maxDialogs} slot until the idle timer expires and a brief M3UA flap turns into a
 * long outage as every OTA retry burns another slot.
 *
 * <p>The jSS7 MAP interfaces are wide and {@code Ss7Stack} is final, so these tests drive
 * {@link MapSmsOutbound} through {@link Proxy} stubs rather than pulling a mock framework into
 * a module that uses none.
 */
public class MapSmsOutboundDialogLeakTest {

    private static final long LOCAL_DIALOG_ID = 4242L;

    @Test
    public void releasesDialogWhenSriSendFails() {
        DialogStub dialog = new DialogStub(new MAPException("m3ua down"), null);
        MapSmsOutbound outbound = outboundFor(dialog);

        IllegalStateException failure = expectFailure(outbound, sriCommand());

        assertTrue("send() must have been attempted", dialog.sendAttempted);
        assertEquals("unsent dialog must be released exactly once", 1, dialog.releaseCount.get());
        assertEquals("correlation entry must not outlive the dialog",
                "fallback", outbound.correlate(LOCAL_DIALOG_ID, "fallback"));
        assertTrue(failure.getCause() instanceof MAPException);
    }

    @Test
    public void releasesDialogWhenMtSendFails() {
        DialogStub dialog = new DialogStub(new MAPException("m3ua down"), null);
        MapSmsOutbound outbound = outboundFor(dialog);

        expectFailure(outbound, mtCommand());

        assertEquals("unsent dialog must be released exactly once", 1, dialog.releaseCount.get());
        assertEquals("fallback", outbound.correlate(LOCAL_DIALOG_ID, "fallback"));
    }

    /** Releasing a dialog that is already on the wire would drop an in-flight SRI/MT. */
    @Test
    public void keepsDialogWhenSendSucceeds() {
        DialogStub dialog = new DialogStub(null, null);
        MapSmsOutbound outbound = outboundFor(dialog);

        assertTrue(outbound.send(sriCommand()));

        assertEquals("a sent dialog must never be released here", 0, dialog.releaseCount.get());
        assertEquals("correlation must survive for the response event",
                "corr-sri", outbound.correlate(LOCAL_DIALOG_ID, "fallback"));
    }

    /** A failing release must not replace the reason the send failed. */
    @Test
    public void releaseFailureDoesNotMaskSendFailure() {
        MAPException cause = new MAPException("m3ua down");
        DialogStub dialog = new DialogStub(cause, new IllegalStateException("dialog already dead"));
        MapSmsOutbound outbound = outboundFor(dialog);

        IllegalStateException failure = expectFailure(outbound, sriCommand());

        assertEquals(1, dialog.releaseCount.get());
        assertSame("original MAP failure must propagate", cause, failure.getCause());
    }

    private static IllegalStateException expectFailure(MapSmsOutbound outbound, Ss7Command cmd) {
        try {
            outbound.send(cmd);
            fail("expected the MAP send failure to propagate");
            throw new AssertionError("unreachable");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
            return e;
        }
    }

    private static MapSmsOutbound outboundFor(DialogStub dialog) {
        MAPDialogSms dialogProxy = stub(MAPDialogSms.class, dialog);
        MAPProvider provider = stub(MAPProvider.class, new ProviderStub(dialogProxy));
        return new MapSmsOutbound(provider, stub(ParameterFactory.class, DEFAULTS));
    }

    private static Ss7Command.MapSendRoutingInfoForSm sriCommand() {
        return new Ss7Command.MapSendRoutingInfoForSm(
                "corr-sri",
                Ss7Address.of("251900000000", 6),
                Ss7Address.of("251900000001", 8),
                "251911000001",
                "251900000000",
                0);
    }

    private static Ss7Command.MapMtForwardSm mtCommand() {
        return new Ss7Command.MapMtForwardSm(
                "corr-mt",
                Ss7Address.of("251900000002", 8),
                Ss7Address.of("251900000001", 8),
                "636010000000001",
                "251900000000",
                new byte[] {0x01, 0x02, 0x03},
                0xF6,
                0x7F,
                false,
                0);
    }

    // --- proxy plumbing ---------------------------------------------------

    private static final class DialogStub implements InvocationHandler {
        final AtomicInteger releaseCount = new AtomicInteger();
        volatile boolean sendAttempted;

        private final Throwable sendFailure;
        private final RuntimeException releaseFailure;

        DialogStub(Throwable sendFailure, RuntimeException releaseFailure) {
            this.sendFailure = sendFailure;
            this.releaseFailure = releaseFailure;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "send":
                    sendAttempted = true;
                    if (sendFailure != null) {
                        throw sendFailure;
                    }
                    return null;
                case "release":
                    releaseCount.incrementAndGet();
                    if (releaseFailure != null) {
                        throw releaseFailure;
                    }
                    return null;
                case "getLocalDialogId":
                    return LOCAL_DIALOG_ID;
                default:
                    return defaultValue(proxy, method, args);
            }
        }
    }

    private static final class ProviderStub implements InvocationHandler {
        private final MAPServiceSms serviceSms;

        ProviderStub(MAPDialogSms dialog) {
            this.serviceSms = stub(MAPServiceSms.class, (proxy, method, args) ->
                    "createNewDialog".equals(method.getName())
                            ? dialog
                            : defaultValue(proxy, method, args));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getMAPServiceSms" -> serviceSms;
                case "getMAPParameterFactory" -> stub(MAPParameterFactory.class, DEFAULTS);
                case "getMAPSmsTpduParameterFactory" ->
                        stub(MAPSmsTpduParameterFactory.class, DEFAULTS);
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static final InvocationHandler DEFAULTS = MapSmsOutboundDialogLeakTest::defaultValue;

    private static <T> T stub(Class<T> iface, InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(
                MapSmsOutboundDialogLeakTest.class.getClassLoader(),
                new Class<?>[] {iface},
                handler));
    }

    /** Benign value for every jSS7 call the code under test does not care about. */
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
