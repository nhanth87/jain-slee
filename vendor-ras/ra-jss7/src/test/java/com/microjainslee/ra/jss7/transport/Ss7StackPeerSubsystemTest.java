/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */
package com.microjainslee.ra.jss7.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.restcomm.protocols.ss7.sccp.RemoteSubSystem;
import org.restcomm.protocols.ss7.sccp.SccpResource;

import com.microjainslee.ra.jss7.Ss7RaConfig;

/**
 * An HLR (SSN 6) answering a VLR (SSN 7): the answer's called party is the peer SSN, which the
 * stack builder never declared — SCCP dropped every answer ("no Remote SubSystem = 7").
 */
public class Ss7StackPeerSubsystemTest {

    @Test
    public void peerSsnIsDeclaredOnTheDestinationPointCode() throws Exception {
        List<int[]> added = new ArrayList<>();
        SccpResource resource = resource(Map.of(1, new int[] {2, 6}), added);
        Ss7Stack.declarePeerSubsystem(resource,
                new Ss7RaConfig().destinationPointCode(2).localSsn(6).remoteSsn(7));
        assertEquals(1, added.size());
        assertEquals("next free id", 2, added.get(0)[0]);
        assertEquals(2, added.get(0)[1]);
        assertEquals(7, added.get(0)[2]);
    }

    @Test
    public void alreadyDeclaredPeerIsLeftAlone() throws Exception {
        List<int[]> added = new ArrayList<>();
        SccpResource resource = resource(Map.of(1, new int[] {2, 7}), added);
        Ss7Stack.declarePeerSubsystem(resource,
                new Ss7RaConfig().destinationPointCode(2).localSsn(7).remoteSsn(7));
        assertTrue(added.isEmpty());
    }

    /** Remote SSNs as id → {spc, ssn}; records addRemoteSsn calls as {id, spc, ssn}. */
    private static SccpResource resource(Map<Integer, int[]> existing, List<int[]> added) {
        Map<Integer, RemoteSubSystem> ssns = new HashMap<>();
        existing.forEach((id, v) -> ssns.put(id, subsystem(v[0], v[1])));
        return (SccpResource) Proxy.newProxyInstance(SccpResource.class.getClassLoader(),
                new Class<?>[] {SccpResource.class}, (p, m, a) -> switch (m.getName()) {
                    case "getRemoteSsns" -> ssns;
                    case "getRemoteSsn" -> a.length == 2
                            ? ssns.values().stream()
                                    .filter(s -> s.getRemoteSpc() == (int) a[0] && s.getRemoteSsn() == (int) a[1])
                                    .findFirst().orElse(null)
                            : ssns.get((int) a[0]);
                    case "addRemoteSsn" -> {
                        added.add(new int[] {(int) a[0], (int) a[1], (int) a[2]});
                        yield null;
                    }
                    default -> null;
                });
    }

    private static RemoteSubSystem subsystem(int spc, int ssn) {
        return (RemoteSubSystem) Proxy.newProxyInstance(RemoteSubSystem.class.getClassLoader(),
                new Class<?>[] {RemoteSubSystem.class}, (p, m, a) -> switch (m.getName()) {
                    case "getRemoteSpc" -> spc;
                    case "getRemoteSsn" -> ssn;
                    default -> m.getReturnType() == boolean.class ? false
                            : m.getReturnType() == int.class ? 0 : null;
                });
    }
}
