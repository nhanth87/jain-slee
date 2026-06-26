/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VirtualThreadSbbEntityPoolTest {

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    @Test
    public void prewarmsConfiguredInstanceCount() {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(4, 16, true);
        try {
            int warmed = pool.prewarm(4);
            assertEquals(4, warmed);
            assertEquals(4, pool.size());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void prewarmRespectsMaxBound() {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(1, 3, true);
        try {
            int warmed = pool.prewarm(10);
            assertEquals(3, warmed);
            assertEquals(3, pool.size());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void acquireReturnsSameEntityForSameSbbId() {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(2, 8, true);
        try {
            AtomicInteger factoryCalls = new AtomicInteger();
            VirtualThreadSbbEntityPool.SbbEntity first =
                    pool.acquire("sbb-A", () -> { factoryCalls.incrementAndGet(); return new NoopSbb(); });
            VirtualThreadSbbEntityPool.SbbEntity second =
                    pool.acquire("sbb-A", () -> { factoryCalls.incrementAndGet(); return new NoopSbb(); });
            assertSame("acquire must return the same entity for the same SBB ID", first, second);
            assertEquals("factory must only be invoked once per SBB ID", 1, factoryCalls.get());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void acquireReturnsDistinctEntitiesForDistinctSbbIds() {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(2, 8, true);
        try {
            VirtualThreadSbbEntityPool.SbbEntity a = pool.acquire("sbb-A", NoopSbb::new);
            VirtualThreadSbbEntityPool.SbbEntity b = pool.acquire("sbb-B", NoopSbb::new);
            assertNotSame(a, b);
            assertEquals(2, pool.size());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void releaseIsIdempotentAndDoesNotLoseEntity() {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(1, 4, true);
        try {
            VirtualThreadSbbEntityPool.SbbEntity e = pool.acquire("sbb-X", NoopSbb::new);
            pool.release(e);
            pool.release(e);
            assertSame(e, pool.acquire("sbb-X", NoopSbb::new));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void submitTasksRunOnSbbOwningThread() throws Exception {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(1, 4, true);
        try {
            VirtualThreadSbbEntityPool.SbbEntity e = pool.acquire("sbb-Thread", NoopSbb::new);
            AtomicInteger seenThreadHash = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);
            e.submit(() -> {
                seenThreadHash.set(System.identityHashCode(Thread.currentThread()));
                done.countDown();
            });
            assertTrue("task must run within timeout", done.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 5; i++) {
                CountDownLatch d = new CountDownLatch(1);
                final int[] holder = new int[1];
                e.submit(() -> { holder[0] = System.identityHashCode(Thread.currentThread()); d.countDown(); });
                assertTrue(d.await(3, TimeUnit.SECONDS));
                assertEquals("all tasks for the same SBB ID must execute on the same thread",
                        seenThreadHash.get(), holder[0]);
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void concurrentEventSubmissionExecutesSeriallyPerSbbId() throws Exception {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(1, 4, true);
        try {
            final int eventCount = 200;
            AtomicInteger nextExpected = new AtomicInteger();
            AtomicInteger reorderings = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(eventCount);

            VirtualThreadSbbEntityPool.SbbEntity e =
                    pool.acquire("sbb-Order", () -> new Sbb() { /* placeholder */ });

            int producerCount = 8;
            ExecutorService producers = Executors.newFixedThreadPool(producerCount);
            try {
                final CountDownLatch start = new CountDownLatch(1);
                for (int p = 0; p < producerCount; p++) {
                    producers.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < eventCount / producerCount; i++) {
                                nextExpected.incrementAndGet();
                                e.submit(() -> {
                                    int inFlight = IN_FLIGHT.incrementAndGet();
                                    if (inFlight != 1) {
                                        reorderings.incrementAndGet();
                                    }
                                    try {
                                        Thread.sleep(0, 500_000);
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                    } finally {
                                        IN_FLIGHT.decrementAndGet();
                                    }
                                    done.countDown();
                                });
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                start.countDown();
                assertTrue("all events must be processed within 10s",
                        done.await(10, TimeUnit.SECONDS));
            } finally {
                producers.shutdownNow();
            }
            assertEquals("no two tasks for the same SBB ID should ever run concurrently",
                    0, reorderings.get());
            assertEquals(eventCount, nextExpected.get());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void shutdownDrainsExecutorAndStopsAccepting() throws Exception {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(1, 4, true);
        VirtualThreadSbbEntityPool.SbbEntity e = pool.acquire("sbb-Drain", NoopSbb::new);
        CountDownLatch ranOnce = new CountDownLatch(1);
        e.submit(ranOnce::countDown);
        assertTrue(ranOnce.await(3, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue("executor must terminate within timeout",
                pool.getExecutor().isTerminated());
        try {
            e.submit(() -> { /* should not run */ });
            fail("submit after shutdown must throw");
        } catch (IllegalStateException expected) {
            // good
        }
    }

    @Test
    public void sbbEntitiesExceptionHandlerIsInvokedOnFailure() throws Exception {
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(1, 4, true);
        try {
            final AtomicInteger excSeen = new AtomicInteger();
            Sbb faulty = new Sbb() {
                @Override
                public void sbbExceptionThrown(Exception e, Object event, ActivityContextInterface aci) {
                    excSeen.incrementAndGet();
                }
            };
            VirtualThreadSbbEntityPool.SbbEntity e = pool.acquire("sbb-Fault", () -> faulty);
            CountDownLatch handled = new CountDownLatch(1);
            e.submit(() -> { handled.countDown(); throw new RuntimeException("boom"); });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && excSeen.get() == 0) {
                Thread.sleep(20);
            }
            assertEquals("sbbExceptionThrown must be invoked once for the failing task",
                    1, excSeen.get());
            assertEquals(0, handled.getCount());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void sbbEventHandlerReceivesRoutedEventsOnOwningThread() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
                        .preferVirtualThreads(false)
                        .sbbPoolMin(2)
                        .sbbPoolMax(8)
                        .sbbPerVirtualThread(true)
                        .build());
        container.start();
        try {
            OrderRecordingSbb sbb = new OrderRecordingSbb();
            com.microjainslee.api.SbbLocalObject localObject = container.registerSbb("sbb-routed", sbb);
            InMemoryActivityContext aci = container.createActivityContext("ac-routed");
            container.attach("ac-routed", localObject);

            List<Thread> observedThreads = Collections.synchronizedList(new ArrayList<Thread>());
            sbb.observedThreads = observedThreads;
            for (int i = 0; i < 5; i++) {
                container.routeEvent(new SimpleEvent(), aci);
            }
            assertTrue("expected 5 events within 5s", sbb.awaitEvents(5, 5));
            assertFalse("expected at least one event", observedThreads.isEmpty());
            Thread first = observedThreads.get(0);
            for (Thread t : observedThreads) {
                assertSame("events for one SBB ID must all be dispatched on its owning thread",
                        first, t);
            }
        } finally {
            container.stop();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMinGreaterThanMax() {
        new VirtualThreadSbbEntityPool(10, 5, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMaxLessThanOne() {
        new VirtualThreadSbbEntityPool(0, 0, true);
    }

    private static final class NoopSbb implements Sbb { }

    private static final class SimpleEvent implements SleeEvent { }

    private static final class OrderRecordingSbb implements Sbb, SleeEventHandler {
        private final CountDownLatch latch = new CountDownLatch(5);
        private final AtomicInteger received = new AtomicInteger();
        volatile List<Thread> observedThreads;

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            received.incrementAndGet();
            if (observedThreads != null) observedThreads.add(Thread.currentThread());
            latch.countDown();
        }

        boolean awaitEvents(int expected, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (received.get() < expected && System.nanoTime() < deadline) {
                if (latch.getCount() == 0) break;
                Thread.sleep(20);
            }
            return received.get() >= expected;
        }
    }
}
