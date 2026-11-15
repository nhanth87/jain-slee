package com.microjainslee.core;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityEndedEvent;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Named activities keep their name (no shared "__pool__" placeholder → no single entity for a
 * whole SBB type), and ending a Null Activity (JSR 240 §7.10.1) returns the entity, slot and
 * naming entry (§7.8.3, §8.5).
 */
public class ActivityLifecycleNoLeakTest {

    public record Ping(String id) implements SleeEvent { }

    public static final class PingSbb implements Sbb, SleeEventHandler {
        static final Set<Integer> instances = ConcurrentHashMap.newKeySet();
        static final AtomicInteger handled = new AtomicInteger();
        static volatile CountDownLatch done;
        @Override public void sbbCreate() { }
        @Override public void sbbActivate() { }
        @Override public void sbbPassivate() { }
        @Override public void sbbRemove() { }
        @Override public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            if (event instanceof Ping) {
                instances.add(System.identityHashCode(this));
                handled.incrementAndGet();
                done.countDown();
            } else if (event instanceof ActivityEndedEvent) {
                // root removal follows
            }
        }
    }

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventDeliveryMode(EventDeliveryMode.ASYNC_COMMIT).build());
        container.start();
        container.registerSbbType(PingSbb.class, PingSbb::new);
        container.mapEventToSbb(Ping.class, "PingSbb");
        PingSbb.instances.clear();
        PingSbb.handled.set(0);
    }

    @After
    public void tearDown() {
        container.stop();
    }

    @Test
    public void namedContextKeepsItsName() {
        assertEquals("pull-42", container.createActivityContext("pull-42").getActivityContextName());
    }

    @Test
    public void oneShotActivitiesAreHandledInParallelEntitiesAndLeaveNothingBehind() throws Exception {
        int n = 2_000;
        PingSbb.done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            // JSR 240 §7.10: create a Null Activity, fire on it, end it.
            ActivityContextInterface aci = container.getNullActivityFactory().createNullActivity("ping-" + i);
            container.routeEvent(new Ping("p" + i), aci);
            ((com.microjainslee.api.NullActivity) aci.getActivity()).endActivity();
        }
        assertTrue(PingSbb.done.await(30, TimeUnit.SECONDS));
        assertEquals(n, PingSbb.handled.get());
        // One entity per activity, not one entity for the whole SBB type.
        assertTrue("events collapsed onto " + PingSbb.instances.size() + " SBB object(s)",
                PingSbb.instances.size() > 1);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && container.getEntityRemovalCount() < n) {
            Thread.sleep(20);
        }
        assertEquals(n, container.getEntityRemovalCount());
        for (int i = 0; i < n; i += 97) {
            assertNull(container.getActivityContextNamingFacility().lookup("ping-" + i));
        }
    }
}
