package com.microjainslee.core;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EntitySlotTest {

    private static final class NoopSbb implements Sbb, SleeEventHandler {
        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        }
    }

    @Test
    public void idleSlotRunsWorkSubmittedLater() throws Exception {
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        try {
            EntitySlot slot = new EntitySlot(vt);
            slot.bind("sbb-1", 1L, new NoopSbb());
            Thread.sleep(300);
            assertTrue("idle slot is parked", slot.isParked());

            CountDownLatch ran = new CountDownLatch(1);
            slot.submit(ran::countDown);
            assertTrue("task runs after a long idle period", ran.await(2, TimeUnit.SECONDS));
        } finally {
            vt.shutdownNow();
        }
    }

    @Test
    public void shutdownEndsIdleLoop() throws Exception {
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        try {
            EntitySlot slot = new EntitySlot(vt);
            slot.bind("sbb-2", 2L, new NoopSbb());
            slot.markShutdown();
            try {
                slot.submit(() -> { });
                fail("submit after shutdown must fail");
            } catch (IllegalStateException expected) {
                // ok
            }
            vt.shutdown();
            assertTrue("idle loop exits on shutdown", vt.awaitTermination(2, TimeUnit.SECONDS));
        } finally {
            vt.shutdownNow();
        }
    }

    /** Idle slots must block, not poll: thousands of idle slots should cost ~no CPU. */
    @Test
    public void manyIdleSlotsDoNotBurnCpu() throws Exception {
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        List<EntitySlot> slots = new ArrayList<>();
        try {
            for (int i = 0; i < 5_000; i++) {
                slots.add(new EntitySlot(vt));
            }
            Thread.sleep(500); // let every loop reach its idle wait

            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long before = os.getProcessCpuTime();
            Thread.sleep(1_000);
            long cpuMs = (os.getProcessCpuTime() - before) / 1_000_000;

            assertTrue("5000 idle slots used " + cpuMs + " ms CPU in 1 s", cpuMs < 400);
        } finally {
            slots.forEach(EntitySlot::markShutdown);
            vt.shutdownNow();
        }
    }
}
