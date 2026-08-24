/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import org.junit.Test;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

public class MapProtocolAdapterErrorTest {

    @Test
    public void unauthorizedLcsClientMapsToTs29002Name() {
        MAPErrorMessage error = error(true, false, 53L);
        assertEquals("unauthorizedLCSClient", MapProtocolAdapter.errorName(error));
    }

    @Test
    public void positionMethodFailureMapsToTs29002Name() {
        MAPErrorMessage error = error(false, true, 27L);
        assertEquals("positionMethodFailure", MapProtocolAdapter.errorName(error));
    }

    @Test
    public void unknownErrorFallsBackToErrorCode() {
        MAPErrorMessage error = error(false, false, 111L);
        assertEquals("errorCode111", MapProtocolAdapter.errorName(error));
    }

    @Test
    public void nullErrorMapsToUnknown() {
        assertEquals("unknown", MapProtocolAdapter.errorName(null));
    }

    private static MAPErrorMessage error(boolean unauthorized, boolean positionMethod, Long code) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "isEmUnauthorizedLCSClient" -> unauthorized;
            case "isEmPositionMethodFailure" -> positionMethod;
            case "getErrorCode" -> code;
            case "toString" -> "stub-error";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
        };
        return (MAPErrorMessage) Proxy.newProxyInstance(
                MapProtocolAdapterErrorTest.class.getClassLoader(),
                new Class<?>[] {MAPErrorMessage.class},
                handler);
    }
}
