package com.microjainslee.core;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * An event whose target entity lost its slot (MISSING_ENTITY) must not run on the router
 * thread in ASYNC_COMMIT: a handler that waits for another event — an S-CSCF waiting for
 * the Cx MAA — would hold the only thread able to deliver it.
 */
public class OrphanEntityDeliveryTest {

    record WaitEvent(String id) implements SleeEvent {}

    record SignalEvent(String id) implements SleeEvent {}

    static final CountDownLatch SIGNAL = new CountDownLatch(1);
    static final AtomicReference<String> WAITER_THREAD = new AtomicReference<>();
    static final AtomicReference<Boolean> WAITER_SAW_SIGNAL = new AtomicReference<>();

    /** Waits (bounded) for the signal another SBB receives, like CxClient.sendSync. */
    public static class WaiterSbb implements Sbb, SleeEventHandler {
        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            if (event instanceof SignalEvent) {
                SIGNAL.countDown();
                return;
            }
            if (event instanceof WaitEvent) {
                WAITER_THREAD.set(Thread.currentThread().getName());
                try {
                    WAITER_SAW_SIGNAL.set(SIGNAL.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static class SignalSbb implements Sbb, SleeEventHandler {
        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            if (event instanceof SignalEvent) {
                SIGNAL.countDown();
            }
        }
    }

    static final class TestRa implements RaEndpointPort, RaCommandPort {
        volatile RaBootstrapPort bootstrap;

        @Override public void activate(RaBootstrapPort bootstrap) { this.bootstrap = bootstrap; }
        @Override public void deactivate() { }
        @Override public String getRaName() { return "orphan-ra"; }
        @Override public void sendCommand(OutboundCommand command) { }
    }

    private MicroSleeContainer container;

    @After
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    public void orphanedEntityDoesNotBlockTheRouter() throws Exception {
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64)
                .eventDeliveryMode(EventDeliveryMode.ASYNC_COMMIT)
                .build());
        container.start();
        container.registerSbbType(WaiterSbb.class, WaiterSbb::new);
        container.registerSbbType(SignalSbb.class, SignalSbb::new);
        TestRa ra = new TestRa();
        container.registerRa(ra, ra);

        SimpleSbbLocalObject waiter = container.acquireEntity("waiter-1", WaiterSbb.class);
        SimpleSbbLocalObject signal = container.acquireEntity("signal-1", SignalSbb.class);
        assertTrue(waiter.awaitReady(5, TimeUnit.SECONDS));
        assertTrue(signal.awaitReady(5, TimeUnit.SECONDS));
        container.createActivityContext("act-wait");
        container.createActivityContext("act-signal");
        container.attach("act-wait", waiter);
        container.attach("act-signal", signal);

        // Orphan the waiter: its slot leaves the pool while it stays attached (MISSING_ENTITY).
        container.getSbbEntityPool().releaseById("waiter-1");

        ActivityHandle wait = ra.bootstrap.createActivityHandle("act-wait");
        ActivityHandle sig = ra.bootstrap.createActivityHandle("act-signal");
        long start = System.nanoTime();
        ra.bootstrap.fireEvent(new WaitEvent("w"), wait, null);
        // The signal goes to the same orphaned entity: only a free router can deliver it
        // while the first handler is still waiting.
        ra.bootstrap.fireEvent(new SignalEvent("s"), wait, null);

        long deadline = System.currentTimeMillis() + 5_000;
        while (WAITER_SAW_SIGNAL.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("orphan handler ran", WAITER_THREAD.get() != null);
        assertNotEquals("not on the router thread", "disruptor-worker", WAITER_THREAD.get());
        assertTrue("the signal reached its SBB while the orphan waited (" + tookMs + " ms, waiter on "
                        + WAITER_THREAD.get() + ", signal count=" + SIGNAL.getCount() + ")",
                Boolean.TRUE.equals(WAITER_SAW_SIGNAL.get()));
        assertTrue("no router stall: " + tookMs + " ms", tookMs < 2_000);
    }
}
