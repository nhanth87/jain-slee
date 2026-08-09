/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.component.Ss7TcapComponent;
import com.microjainslee.ra.jss7.event.Ss7Event;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Gate A — ra-jss7 checkpoints SBB entity on TCAP Begin and
 * Continue-with-components (inbound + outbound). Empty Continue does not.
 */
public class Jss7GateACheckpointTest {

    private Ss7ResourceAdaptor ra;
    private AtomicInteger checkpoints;

    @Before
    public void setUp() throws Exception {
        ra = new Ss7ResourceAdaptor();
        checkpoints = new AtomicInteger();
        ra.checkpointBridge().bindFunction(id -> {
            checkpoints.incrementAndGet();
            return true;
        });
        ra.setBootstrapPort(new RaBootstrapPort() {
            @Override
            public ActivityHandle createActivityHandle(String id) {
                return () -> id; // ActivityHandle#getId
            }

            @Override
            public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                // no-op for Gate A unit test
            }
        });
        Field activeField = Ss7ResourceAdaptor.class.getDeclaredField("active");
        activeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicBoolean active = (AtomicBoolean) activeField.get(ra);
        active.set(true);
    }

    @Test
    public void inboundBeginCheckpoints() {
        Ss7Address addr = Ss7Address.of("1234", 8);
        ra.publish("42", new Ss7Event.TcapBegin(
                "42", addr, addr, 1, List.of(), true, 0));
        assertEquals(1, checkpoints.get());
    }

    @Test
    public void inboundContinueWithComponentsCheckpoints() {
        Ss7Address addr = Ss7Address.of("1234", 8);
        ra.publish("42", new Ss7Event.TcapBegin(
                "42", addr, addr, 1, List.of(), true, 0));
        checkpoints.set(0);
        ra.publish("42", new Ss7Event.TcapContinue(
                "42", addr,
                List.of(new Ss7TcapComponent.Invoke(1L, 59, new byte[]{1}, true, 0)),
                0));
        assertEquals(1, checkpoints.get());
    }

    @Test
    public void inboundEmptyContinueDoesNotCheckpoint() {
        Ss7Address addr = Ss7Address.of("1234", 8);
        ra.publish("42", new Ss7Event.TcapBegin(
                "42", addr, addr, 1, List.of(), true, 0));
        checkpoints.set(0);
        ra.publish("42", new Ss7Event.TcapContinue("42", addr, List.of(), 0));
        assertEquals(0, checkpoints.get());
    }

    @Test
    public void outboundBeginAndContinueWithComponentsCheckpoint() {
        Ss7Address addr = Ss7Address.of("1234", 8);
        ra.sendOutboundLocal(new Ss7Command.TcapBegin(
                "99", addr, addr, 1, List.of(), 0));
        assertEquals(1, checkpoints.get());
        checkpoints.set(0);
        ra.sendOutboundLocal(new Ss7Command.TcapContinue(
                "99", addr, List.of(), 0));
        assertEquals("empty Continue must not checkpoint", 0, checkpoints.get());
        ra.sendOutboundLocal(new Ss7Command.TcapContinue(
                "99", addr,
                List.of(new Ss7TcapComponent.Invoke(1L, 59, new byte[]{2}, true, 0)),
                0));
        assertEquals(1, checkpoints.get());
        assertTrue(ra.checkpointBridge().metrics().raCheckpointOkCount() >= 2);
    }
}
