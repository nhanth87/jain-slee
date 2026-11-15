package com.microjainslee.core;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.SessionKeyedCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Several entities of one RA behind one link name: sticky per session, round-robin otherwise. */
public class RaEntityGroupTest {

    record Send(String sessionKey) implements SessionKeyedCommand { }
    record Broadcast() implements OutboundCommand { }

    static final class FakeEntity implements RaEndpointPort, RaCommandPort {
        final String name;
        final List<OutboundCommand> received = new CopyOnWriteArrayList<>();
        boolean active;
        FakeEntity(String name) { this.name = name; }
        @Override public void activate(RaBootstrapPort bootstrap) { active = true; }
        @Override public void deactivate() { active = false; }
        @Override public String getRaName() { return name; }
        @Override public void sendCommand(OutboundCommand command) { received.add(command); }
    }

    private MicroSleeContainer container;
    private final List<FakeEntity> entities = new ArrayList<>();

    @Before
    public void setUp() {
        container = new MicroSleeContainer();
        container.start();
        for (int i = 0; i < 4; i++) {
            entities.add(new FakeEntity("http-callback-ra#" + i));
        }
        container.registerRaEntityGroup("http-callback-ra", entities);
    }

    @After
    public void tearDown() {
        container.stop();
    }

    @Test
    public void everyEntityIsAnActiveRaOfItsOwn() {
        for (FakeEntity e : entities) {
            assertTrue(e.name + " not activated", e.active);
            assertNotNull(container.getRaCommandPort(e.name));
        }
    }

    @Test
    public void aSessionAlwaysReachesTheSameEntity() {
        RaCommandPort link = container.getRaCommandPort("http-callback-ra");
        for (int turn = 0; turn < 5; turn++) {
            link.sendCommand(new Send("dialog-42"));
        }
        long holders = entities.stream().filter(e -> !e.received.isEmpty()).count();
        assertEquals(1, holders);
    }

    @Test
    public void sessionsSpreadOverAllEntities() {
        RaCommandPort link = container.getRaCommandPort("http-callback-ra");
        for (int i = 0; i < 400; i++) {
            link.sendCommand(new Send("dialog-" + i));
        }
        for (FakeEntity e : entities) {
            assertTrue(e.name + " got " + e.received.size(), e.received.size() > 50);
        }
    }

    @Test
    public void commandsWithoutASessionGoRoundRobin() {
        RaCommandPort link = container.getRaCommandPort("http-callback-ra");
        for (int i = 0; i < 8; i++) {
            link.sendCommand(new Broadcast());
        }
        Set<Integer> sizes = new HashSet<>();
        entities.forEach(e -> sizes.add(e.received.size()));
        assertEquals(Set.of(2), sizes);
    }
}
