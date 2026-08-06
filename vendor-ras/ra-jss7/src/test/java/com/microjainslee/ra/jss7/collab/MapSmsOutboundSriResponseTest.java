/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.Test;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.LMSI;
import org.restcomm.protocols.ss7.map.api.service.sms.LocationInfoWithLMSI;
import org.restcomm.protocols.ss7.map.api.service.sms.MAPDialogSms;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** HLR-face SRI-SM response on an existing MAP SMS dialog. */
public class MapSmsOutboundSriResponseTest {

    private static final long LOCAL_DIALOG_ID = 77L;

    @Test
    public void replySriAddsResponseAndCloses() {
        AtomicLong invokeSeen = new AtomicLong(-1);
        AtomicReference<String> imsiSeen = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();

        InvocationHandler dialogHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "addSendRoutingInfoForSMResponse":
                    invokeSeen.set((Long) args[0]);
                    IMSI imsi = (IMSI) args[1];
                    if (imsi != null) {
                        imsiSeen.set(imsi.getData());
                    }
                    return null;
                case "close":
                    closed.set(true);
                    return null;
                case "getLocalDialogId":
                    return LOCAL_DIALOG_ID;
                default:
                    return defaultValue(proxy, method, args);
            }
        };

        MAPDialogSms dialog = stub(MAPDialogSms.class, dialogHandler);
        MAPProvider provider = stub(MAPProvider.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getMAPDialog" -> dialog;
                    case "getMAPParameterFactory" -> stub(MAPParameterFactory.class, (p, m, a) ->
                            switch (m.getName()) {
                                case "createIMSI" -> stub(IMSI.class, (ip, im, ia) ->
                                        "getData".equals(im.getName()) ? a[0] : defaultValue(ip, im, ia));
                                case "createISDNAddressString" -> stub(ISDNAddressString.class, DEFAULTS);
                                case "createLMSI" -> stub(LMSI.class, DEFAULTS);
                                case "createLocationInfoWithLMSI" -> stub(LocationInfoWithLMSI.class, DEFAULTS);
                                default -> defaultValue(p, m, a);
                            });
                    default -> defaultValue(proxy, method, args);
                });

        MapSmsOutbound outbound = new MapSmsOutbound(provider, stub(ParameterFactory.class, DEFAULTS));
        assertTrue(outbound.send(new Ss7Command.MapSendRoutingInfoForSmResponse(
                String.valueOf(LOCAL_DIALOG_ID), 9L, "636010000000001", "251911000099")));

        assertEquals(9L, invokeSeen.get());
        assertEquals("636010000000001", imsiSeen.get());
        assertTrue(closed.get());
        assertNotNull(outbound);
    }

    private static final InvocationHandler DEFAULTS = MapSmsOutboundSriResponseTest::defaultValue;

    private static <T> T stub(Class<T> iface, InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(
                MapSmsOutboundSriResponseTest.class.getClassLoader(),
                new Class<?>[] {iface},
                handler));
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString" -> {
                return "stub:" + method.getDeclaringClass().getSimpleName();
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == args[0];
            }
            default -> {
            }
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
        if (type == void.class) {
            return null;
        }
        return 0;
    }
}
