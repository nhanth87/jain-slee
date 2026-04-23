/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 * This file incorporates work covered by the following copyright contributed under the GNU LGPL : Copyright 2007-2011 Red Hat.
 */
package org.mobicents.slee.runtime.eventrouter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.mobicents.slee.container.SleeContainer;
import org.mobicents.slee.container.activity.ActivityContextHandle;
import org.mobicents.slee.container.event.EventContext;
import org.mobicents.slee.container.eventrouter.EventRouterExecutor;
import org.mobicents.slee.container.eventrouter.EventRoutingTask;
import org.mobicents.slee.container.eventrouter.stats.EventRouterExecutorStatistics;
import org.mobicents.slee.runtime.eventrouter.routingtask.EventRoutingTaskImpl;
import org.mobicents.slee.runtime.eventrouter.stats.EventRouterExecutorStatisticsImpl;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

/**
 * HIGH PERFORMANCE Event Router Executor using LMAX Disruptor 3.4.4.
 * Optimized for modern hardware with 16-32 CPU cores and 16-64GB RAM.
 *
 * Features:
 * - Lock-free event processing via Disruptor RingBuffer
 * - Configurable ring size (default 32768 for high throughput)
 * - Multiple worker threads for parallel event processing
 * - Configurable wait strategy (BusySpin for lowest latency)
 * - Statistics collection without adding lock contention
 *
 * @author Performance Optimization Team
 * @author Original: martins
 */
public class DisruptorEventRouterExecutorImpl implements EventRouterExecutor {

    private static final Logger logger = Logger.getLogger(DisruptorEventRouterExecutorImpl.class);

    // System properties for configuration
    private static final int DEFAULT_RING_SIZE = Integer.getInteger("jainslee.eventrouter.ringsize", 262144);
    private static final String WAIT_STRATEGY = System.getProperty("jainslee.eventrouter.waitstrategy", "busyspin");
    private static final boolean USE_MULTI_PRODUCER = Boolean.getBoolean("jainslee.eventrouter.multi.producer");

    /**
     * Event object for the Disruptor ring buffer.
     * Reused to avoid object allocation overhead.
     */
    public static final class Event {
        private EventContext eventContext;
        private SleeContainer sleeContainer;
        private long timestamp;
        private EventRouterExecutorStatisticsImpl stats;
        private CountDownLatch completionLatch;

        public Event init(EventContext eventContext, SleeContainer sleeContainer,
                        EventRouterExecutorStatisticsImpl stats, CountDownLatch completionLatch) {
            this.eventContext = eventContext;
            this.sleeContainer = sleeContainer;
            this.timestamp = System.nanoTime();
            this.stats = stats;
            this.completionLatch = completionLatch;
            return this;
        }

        public void reset() {
            this.eventContext = null;
            this.sleeContainer = null;
            this.stats = null;
            this.completionLatch = null;
        }

        public EventContext getEventContext() {
            return eventContext;
        }

        public SleeContainer getSleeContainer() {
            return sleeContainer;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void process() {
            try {
                EventRoutingTaskImpl task = new EventRoutingTaskImpl(eventContext, sleeContainer);
                task.run();
            } finally {
                if (stats != null) {
                    stats.eventRouted(eventContext.getEventTypeId(), System.nanoTime() - timestamp);
                }
                if (completionLatch != null) {
                    completionLatch.countDown();
                }
            }
        }
    }

    /**
     * Factory for creating Event objects in the ring buffer
     */
    public static final EventFactory<Event> EVENT_FACTORY = new EventFactory<Event>() {
        @Override
        public Event newInstance() {
            return new Event();
        }
    };

    private final RingBuffer<Event> ringBuffer;
    private final Disruptor<Event> disruptor;
    private final SleeContainer sleeContainer;
    private final EventRouterExecutorStatisticsImpl stats;
    private final int workerCount;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile boolean isRunning = false;

    /**
     * Creates a new Disruptor-based Event Router Executor.
     *
     * @param collectStats whether to collect statistics
     * @param threadFactory factory for creating worker threads
     * @param sleeContainer the SLEE container
     * @param ringSize size of the ring buffer (must be power of 2)
     * @param workerCount number of worker threads
     */
    public DisruptorEventRouterExecutorImpl(boolean collectStats,
            java.util.concurrent.ThreadFactory threadFactory,
            SleeContainer sleeContainer,
            int ringSize,
            int workerCount) {

        this.sleeContainer = sleeContainer;
        this.stats = collectStats ? new EventRouterExecutorStatisticsImpl(null) : null;
        this.workerCount = workerCount;

        // Validate ring size is power of 2
        if (ringSize < 1024) {
            ringSize = 1024;
        }
        // Round up to next power of 2
        ringSize = Integer.highestOneBit(ringSize - 1) << 1;
        if (ringSize < 0) {
            ringSize = DEFAULT_RING_SIZE;
        }

        // Select wait strategy based on configuration
        WaitStrategy waitStrategy = selectWaitStrategy();

        // Create work handlers
        @SuppressWarnings("unchecked")
        WorkHandler<Event>[] workHandlers = new WorkHandler[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workHandlers[i] = new EventWorkHandler(stats);
        }

        // Create disruptor using DSL
        this.disruptor = new Disruptor<Event>(
            EVENT_FACTORY,
            ringSize,
            threadFactory,
            USE_MULTI_PRODUCER ? ProducerType.MULTI : ProducerType.SINGLE,
            waitStrategy
        );
        this.disruptor.handleEventsWithWorkerPool(workHandlers);
        this.ringBuffer = this.disruptor.getRingBuffer();

        logger.info(String.format("DisruptorEventRouterExecutor initialized: ringSize=%d, workers=%d, strategy=%s",
            ringSize, workerCount, WAIT_STRATEGY));
    }

    /**
     * Convenience constructor with default ring size and worker count.
     */
    public DisruptorEventRouterExecutorImpl(boolean collectStats,
            java.util.concurrent.ThreadFactory threadFactory,
            SleeContainer sleeContainer) {
        this(collectStats, threadFactory, sleeContainer, DEFAULT_RING_SIZE,
            Integer.getInteger("jainslee.eventrouter.threads",
                Math.max(4, Runtime.getRuntime().availableProcessors())));
    }

    private WaitStrategy selectWaitStrategy() {
        switch (WAIT_STRATEGY.toLowerCase()) {
            case "busyspin":
                return new BusySpinWaitStrategy();
            case "blocking":
                return new BlockingWaitStrategy();
            default:
                // Default to busy spin for lowest latency on modern hardware
                return new BusySpinWaitStrategy();
        }
    }

    public void onStart() {
        isRunning = true;
        this.disruptor.start();
    }

    public void onShutdown() {
        isRunning = false;
        this.disruptor.halt();
        shutdownLatch.countDown();
    }

    @Override
    public EventRouterExecutorStatistics getStatistics() {
        return stats;
    }

    @Override
    public void shutdown() {
        onShutdown();
        try {
            shutdownLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void execute(Runnable task) {
        long sequenceId = ringBuffer.next();
        Event event = ringBuffer.get(sequenceId);
        event.init(null, null, stats, null);

        // Execute the task directly in the producer thread for misc tasks
        try {
            if (stats != null) {
                long start = System.nanoTime();
                task.run();
                stats.miscTaskExecuted(System.nanoTime() - start);
            } else {
                task.run();
            }
        } finally {
            ringBuffer.publish(sequenceId);
        }
    }

    @Override
    public void executeNow(Runnable task) throws InterruptedException, ExecutionException {
        CountDownLatch latch = new CountDownLatch(1);
        long sequenceId = ringBuffer.next();
        Event event = ringBuffer.get(sequenceId);
        event.init(null, null, stats, latch);
        ringBuffer.publish(sequenceId);
        latch.await();
    }

    @Override
    public void activityMapped(ActivityContextHandle ach) {
        if (stats != null) {
            stats.activityMapped(ach);
        }
    }

    @Override
    public void activityUnmapped(ActivityContextHandle ach) {
        if (stats != null) {
            stats.activityUnmapped(ach);
        }
    }

    @Override
    public void routeEvent(EventContext event) {
        long sequenceId = ringBuffer.next();
        Event disruptorEvent = ringBuffer.get(sequenceId);
        disruptorEvent.init(event, sleeContainer, stats, null);
        ringBuffer.publish(sequenceId);
    }

    /**
     * Get the current ring buffer size
     */
    public long getRingBufferSize() {
        return ringBuffer.getBufferSize();
    }

    /**
     * Get the current ring buffer fill level (for monitoring)
     */
    public long getRingBufferFillLevel() {
        return ringBuffer.getBufferSize() - ringBuffer.remainingCapacity();
    }

    /**
     * Get utilization percentage of the ring buffer
     */
    public double getRingBufferUtilization() {
        long fillLevel = getRingBufferFillLevel();
        return (fillLevel * 100.0) / getRingBufferSize();
    }

    /**
     * Internal work handler for processing events from the ring buffer
     */
    private static class EventWorkHandler implements WorkHandler<Event> {
        private static final Logger log = Logger.getLogger(EventWorkHandler.class);

        private final EventRouterExecutorStatisticsImpl stats;

        EventWorkHandler(EventRouterExecutorStatisticsImpl stats) {
            this.stats = stats;
        }

        @Override
        public void onEvent(Event event) throws Exception {
            try {
                event.process();
            } catch (Exception e) {
                log.error("Error processing event", e);
            } finally {
                event.reset();
            }
        }
    }

    @Override
    public String toString() {
        return String.format("DisruptorEventRouterExecutor[ringSize=%d, workers=%d, fillLevel=%d]",
            getRingBufferSize(), workerCount, getRingBufferFillLevel());
    }
}
