/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityEndedEvent;
import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InitialEventSelect;
import com.microjainslee.core.ies.InitialEventSelectCondition;
import com.microjainslee.core.ies.InitialEventSelectResult;
import com.microjainslee.core.removal.EntityRemovalEvent;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * JSR 240 (JAIN SLEE 1.1) §7.3.4 / §8.5 / §6.8 — after {@link ActivityEndedEvent} the SLEE
 * implicitly detaches every SBB; a SLEE-created root entity whose attachment count (own
 * attachments + its children's) reaches zero is removed with its tree, children first.
 */
@RunWith(Parameterized.class)
public class ActivityEndRootRemovalTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> modes() {
        return Arrays.asList(new Object[][] {{EventDeliveryMode.SYNC}, {EventDeliveryMode.ASYNC_COMMIT}});
    }

    private final EventDeliveryMode mode;
    private MicroSleeContainer container;
    private TestRa ra;
    private final List<EntityRemovalEvent> removals = new CopyOnWriteArrayList<>();

    /** Events each SBB saw, as "entityTag:EventSimpleName". */
    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    public ActivityEndRootRemovalTest(EventDeliveryMode mode) {
        this.mode = mode;
    }

    record CallEvent(String callId) implements SleeEvent {}

    record SessionEvent(String sessionKey) implements SleeEvent {}

    /** One entity per activity — no IES (Elisa ScscfInviteSbb shape). */
    public static class PerCallSbb implements Sbb, SleeEventHandler {
        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            SEEN.add("call:" + event.getClass().getSimpleName());
        }
    }

    /** IES-converged session SBB (USSD shape). */
    public static class SessionSbb implements Sbb, SleeEventHandler {
        public SessionSbb() {
        }

        @InitialEventSelect(name = "session")
        public InitialEventSelectResult select(InitialEventSelectCondition c) {
            if (c.getEvent() instanceof SessionEvent e) {
                return InitialEventSelectResult.forSession(e.sessionKey(), true);
            }
            return InitialEventSelectResult.builder().initialEvent(false).build();
        }

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            SEEN.add("session:" + event.getClass().getSimpleName());
        }
    }

    static final class TestRa implements RaEndpointPort, RaCommandPort {
        volatile RaBootstrapPort bootstrap;

        @Override
        public void activate(RaBootstrapPort bootstrap) {
            this.bootstrap = bootstrap;
        }

        @Override
        public void deactivate() {
        }

        @Override
        public String getRaName() {
            return "test-ra";
        }

        @Override
        public void sendCommand(OutboundCommand command) {
        }
    }

    private void start() {
        SEEN.clear();
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64)
                .eventDeliveryMode(mode)
                .build());
        container.start();
        container.registerSbbType(PerCallSbb.class, PerCallSbb::new);
        container.registerSbbType(SessionSbb.class, SessionSbb::new);
        container.createIesDispatcher();
        container.mapEventToSbb(CallEvent.class, "PerCallSbb");
        container.mapEventToSbb(SessionEvent.class, "SessionSbb");
        container.addEntityRemovalListener(removals::add);
        ra = new TestRa();
        container.registerRa(ra, ra);
    }

    @After
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    public void perActivityEntityIsRemovedAfterItSawActivityEnded() throws Exception {
        start();
        for (int i = 0; i < 20; i++) {
            ActivityHandle h = ra.bootstrap.createActivityHandle("call-" + i);
            ra.bootstrap.fireEvent(new CallEvent("call-" + i), h, null);
            ra.bootstrap.endActivity(h);
        }

        awaitTrue(() -> removals.stream()
                .filter(r -> r.reason() == EntityRemovalEvent.RemovalReason.ATTACHMENT_COUNT_ZERO).count() == 20);
        assertEquals(20, SEEN.stream().filter("call:CallEvent"::equals).count());
        assertEquals("handler sees ActivityEndedEvent before removal",
                20, SEEN.stream().filter("call:ActivityEndedEvent"::equals).count());
    }

    @Test
    public void iesConvergedEntityLivesWhileAnyActivityIsAttached() throws Exception {
        start();
        ActivityHandle http = ra.bootstrap.createActivityHandle("http-1");
        ActivityHandle map = ra.bootstrap.createActivityHandle("map-1");
        ra.bootstrap.fireEvent(new SessionEvent("msisdn-1"), http, null);
        ra.bootstrap.fireEvent(new SessionEvent("msisdn-1"), map, null);
        awaitTrue(() -> SEEN.stream().filter("session:SessionEvent"::equals).count() == 2);

        ra.bootstrap.endActivity(http);
        awaitTrue(() -> SEEN.contains("session:ActivityEndedEvent"));
        Thread.sleep(200);
        assertTrue("still attached to map-1", removals.isEmpty());

        ra.bootstrap.endActivity(map);
        awaitTrue(() -> removals.size() == 1);
        assertEquals(EntityRemovalEvent.RemovalReason.ATTACHMENT_COUNT_ZERO, removals.get(0).reason());
    }

    /** Detaches itself from the activity inside onEvent (§7.4 ActivityContextInterface.detach). */
    public static class SelfDetachingSbb implements Sbb, SleeEventHandler {
        private volatile com.microjainslee.api.SbbContext ctx;

        @Override
        public void setSbbContext(com.microjainslee.api.SbbContext context) {
            this.ctx = context;
        }

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            SEEN.add("detacher:" + event.getClass().getSimpleName());
            aci.detach(ctx.getSbbLocalObject());
        }
    }

    record DetachEvent(String id) implements SleeEvent {}

    @Test
    public void explicitDetachToZeroRemovesRootAfterCommit() throws Exception {
        start();
        container.registerSbbType(SelfDetachingSbb.class, SelfDetachingSbb::new);
        container.mapEventToSbb(DetachEvent.class, "SelfDetachingSbb");
        ActivityHandle h = ra.bootstrap.createActivityHandle("d-1");
        ra.bootstrap.fireEvent(new DetachEvent("d-1"), h, null);

        awaitTrue(() -> removals.size() == 1);
        assertEquals(EntityRemovalEvent.RemovalReason.ATTACHMENT_COUNT_ZERO, removals.get(0).reason());
        assertEquals(List.of("detacher:DetachEvent"), SEEN);
    }

    @Test
    public void childAttachmentKeepsRootAliveAndTreeIsRemovedChildFirst() throws Exception {
        start();
        ActivityHandle a1 = ra.bootstrap.createActivityHandle("a-1");
        ra.bootstrap.fireEvent(new CallEvent("a-1"), a1, null);
        awaitTrue(() -> SEEN.contains("call:CallEvent"));
        SimpleSbbLocalObject root = (SimpleSbbLocalObject) ((InMemoryActivityContext)
                container.getActivityContextNamingFacility().lookup("a-1")).getAttachedSbbs().get(0);
        String rootId = root.getSbbID().getId();

        SimpleSbbLocalObject child = (SimpleSbbLocalObject) container.createChildSbb(
                rootId, "child-1", id -> new PerCallSbb());
        root.getEntityState().registerChildRelation(childRelationOf(rootId, child));
        ActivityHandle a2 = ra.bootstrap.createActivityHandle("a-2");
        ((InMemoryActivityContext) container.getActivityContextNamingFacility().lookup("a-2"))
                .attachImmediate(child);
        assertEquals("own + child attachment", 2, root.getEntityState().getAttachmentCount());

        ra.bootstrap.endActivity(a1);
        Thread.sleep(300);
        assertTrue("child still attached to a-2", removals.isEmpty());
        assertEquals(1, root.getEntityState().getAttachmentCount());

        ra.bootstrap.endActivity(a2);
        awaitTrue(() -> removals.size() == 2);
        assertEquals("child removed before its parent", "child-1", removals.get(0).entityId());
        assertEquals(EntityRemovalEvent.RemovalReason.CASCADE_CHILD, removals.get(0).reason());
        assertEquals(rootId, removals.get(1).entityId());
        assertEquals(EntityRemovalEvent.RemovalReason.ATTACHMENT_COUNT_ZERO, removals.get(1).reason());
    }

    private static ChildRelationImpl childRelationOf(String parentId, SimpleSbbLocalObject child) {
        ChildRelationImpl rel = new ChildRelationImpl(parentId, p -> child);
        try {
            rel.create();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return rel;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s; seen=" + SEEN);
            }
            Thread.sleep(10);
        }
    }
}
