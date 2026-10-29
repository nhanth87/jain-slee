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
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageFactory;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessageExtensionContainer;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationQuintuplet;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationSetList;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationTriplet;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.QuintupletList;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.TripletList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** HLR/AuC face: MAP SendAuthenticationInfo answer + MAP error on the received dialog. */
public class MapMobilityOutboundTest {

    private static final long DIALOG = 4711L;

    private final AtomicLong invoke = new AtomicLong(-1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Object> setList = new AtomicReference<>();
    private final List<byte[][]> quintuplets = new ArrayList<>();
    private final List<byte[][]> triplets = new ArrayList<>();
    private final AtomicLong errorCode = new AtomicLong(-1);

    private MapMobilityOutbound outbound() {
        MAPDialogMobility dialog = stub(MAPDialogMobility.class, (p, m, a) -> switch (m.getName()) {
            case "addSendAuthenticationInfoResponse" -> {
                invoke.set((Long) a[0]);
                setList.set(a[1]);
                yield null;
            }
            case "sendErrorComponent" -> {
                invoke.set((Long) a[0]);
                yield null;
            }
            case "close" -> {
                closed.set(true);
                yield null;
            }
            default -> defaultValue(p, m, a);
        });
        MAPParameterFactory pf = stub(MAPParameterFactory.class, (p, m, a) -> switch (m.getName()) {
            case "createAuthenticationQuintuplet" -> {
                quintuplets.add(new byte[][] {(byte[]) a[0], (byte[]) a[1], (byte[]) a[2], (byte[]) a[3], (byte[]) a[4]});
                yield stub(AuthenticationQuintuplet.class, DEFAULTS);
            }
            case "createAuthenticationTriplet" -> {
                triplets.add(new byte[][] {(byte[]) a[0], (byte[]) a[1], (byte[]) a[2]});
                yield stub(AuthenticationTriplet.class, DEFAULTS);
            }
            case "createQuintupletList" -> stub(QuintupletList.class, DEFAULTS);
            case "createTripletList" -> stub(TripletList.class, DEFAULTS);
            case "createAuthenticationSetList" -> stub(AuthenticationSetList.class, DEFAULTS);
            default -> defaultValue(p, m, a);
        });
        MAPErrorMessageFactory ef = stub(MAPErrorMessageFactory.class, (p, m, a) -> {
            if ("createMAPErrorMessageExtensionContainer".equals(m.getName())) {
                errorCode.set((Long) a[0]);
                return stub(MAPErrorMessageExtensionContainer.class, DEFAULTS);
            }
            return defaultValue(p, m, a);
        });
        MAPProvider provider = stub(MAPProvider.class, (p, m, a) -> switch (m.getName()) {
            case "getMAPDialog" -> (Long) a[0] == DIALOG ? dialog : null;
            case "getMAPParameterFactory" -> pf;
            case "getMAPErrorMessageFactory" -> ef;
            default -> defaultValue(p, m, a);
        });
        return new MapMobilityOutbound(provider);
    }

    @Test
    public void quintupletsAreEncodedInOrderAndDialogClosed() {
        byte[] r1 = {1}, x1 = {2}, ck1 = {3}, ik1 = {4}, a1 = {5};
        assertTrue(outbound().send(new Ss7Command.MapSendAuthenticationInfoResponse(
                String.valueOf(DIALOG), 3L,
                List.of(new Ss7Command.AuthQuintuplet(r1, x1, ck1, ik1, a1),
                        new Ss7Command.AuthQuintuplet(new byte[] {9}, x1, ck1, ik1, a1)),
                null)));
        assertEquals(3L, invoke.get());
        assertEquals(2, quintuplets.size());
        assertArrayEquals(r1, quintuplets.get(0)[0]);
        assertArrayEquals(a1, quintuplets.get(0)[4]);
        assertTrue(setList.get() != null && closed.get());
    }

    @Test
    public void tripletsForGsmServingNodes() {
        assertTrue(outbound().send(new Ss7Command.MapSendAuthenticationInfoResponse(
                String.valueOf(DIALOG), 1L, null,
                List.of(new Ss7Command.AuthTriplet(new byte[16], new byte[4], new byte[8])))));
        assertEquals(1, triplets.size());
        assertEquals(4, triplets.get(0)[1].length);
        assertTrue(closed.get());
    }

    @Test
    public void unknownSubscriberIsAMapError() {
        assertTrue(outbound().send(new Ss7Command.MapReturnError(String.valueOf(DIALOG), 7L, 1L)));
        assertEquals(1L, errorCode.get());
        assertEquals(7L, invoke.get());
        assertTrue(closed.get());
    }

    @Test
    public void exactlyOneVectorKind() {
        try {
            new Ss7Command.MapSendAuthenticationInfoResponse("1", 1L, null, null);
            fail("empty answer must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static final InvocationHandler DEFAULTS = MapMobilityOutboundTest::defaultValue;

    private static <T> T stub(Class<T> iface, InvocationHandler handler) {
        return iface.cast(Proxy.newProxyInstance(MapMobilityOutboundTest.class.getClassLoader(),
                new Class<?>[] {iface}, handler));
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
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}
