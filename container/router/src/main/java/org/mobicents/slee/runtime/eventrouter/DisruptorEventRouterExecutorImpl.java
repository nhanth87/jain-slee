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
import org.mobicents.slee.container.eventrouter.stats.EventRouterExecutorStatistics;
import org.mobicents.slee.runtime.eventrouter.routingtask.EventRoutingTaskPool;
import org.mobicents.slee.runtime.eventrouter.stats.EventRouterExecutorStatisticsImpl;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

/**
 * HIGH PERFORMANCE Event Router Executor using LMAX Disruptor 3.4.4.
 *
 * Each executor owns one ring buffer and one worker thread. Parallelism across
 * activities is achieved by creating multiple executors in EventRouterImpl.
 *
 * @author Performance Optimization Team
 * @author Original: martins
 */
public class DisruptorEventRouterExecutorImpl implements EventRouterExecutor {

    private static final Logger logger = Logger.getLogger(DisruptorEventRouterExecutorImpl.class);

    private static final int DEFAULT_RING_SIZE = 262144;

    /**
     * Ring buffer slot: either an event routing task or a misc Runnable.
     */
    public static final class Event {
        private EventContext eventContext;
        private SleeContainer sleeContainer;
        private Runnable miscTask;
        private long timestamp;
        private EventRouterExecutorStatisticsImpl stats;
        private CountDownLatch completionLatch;

        public Event initRoute(EventContext eventContext, SleeContainer sleeContainer,
                EventRouterExecutorStatisticsImpl stats, CountDownLatch completionLatch) {
            this.eventContext = eventContext;
            this.sleeContainer = sleeContainer;
            this.miscTask = null;
            this.timestamp = stats != null ? System.nanoTime() : 0L;
            this.stats = stats;
            this.completionLatch = completionLatch;
            return this;
        }

        public Event initMisc(Runnable miscTask, EventRouterExecutorStatisticsImpl stats,
                CountDownLatch completionLatch) {
            this.eventContext = null;
            this.sleeContainer = null;
            this.miscTask = miscTask;
            this.timestamp = stats != null ? System.nanoTime() : 0L;
            this.stats = stats;
            this.completionLatch = completionLatch;
            return this;
        }

        public void reset() {
            this.eventContext = null;
            this.sleeContainer = null;
            this.miscTask = null;
            this.stats = null;
            this.completionLatch = null;
        }

        public void process(boolean endOfBatch) {
            try {
                if (miscTask != null) {
                    if (stats != null) {
                        final long start = timestamp;
                        miscTask.run();
                        stats.miscTaskExecuted(System.nanoTime() - start);
                    } else {
                        miscTask.run();
                    }
                } else if (eventContext != null) {
                    EventRoutingTaskPool.route(eventContext, sleeContainer);
                    if (stats != null) {
                        stats.eventRouted(eventContext.getEventTypeId(), System.nanoTime() - timestamp);
                    }
                }
            } finally {
                if (completionLatch != null) {
                    completionLatch.countDown();
                }
                if (endOfBatch) {
                    EventRouterBatchHooks.onEndOfBatch();
                }
            }
        }
    }

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
    private final int executorIndex;
    private final String waitStrategyName;
    private final int ringSize;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public DisruptorEventRouterExecutorImpl(boolean collectStats,
            java.util.concurrent.ThreadFactory threadFactory,
            SleeContainer sleeContainer,
            int ringSize,
            int workerCount,
            String waitStrategy,
            boolean multiProducer,
            int executorIndex) {

        this.sleeContainer = sleeContainer;
        this.stats = collectStats ? new EventRouterExecutorStatisticsImpl(null) : null;
        this.workerCount = workerCount;
        this.executorIndex = executorIndex;
        this.waitStrategyName = waitStrategy != null ? waitStrategy : "blocking";

        if (ringSize < 1024) {
            ringSize = 1024;
        }
        ringSize = Integer.highestOneBit(ringSize - 1) << 1;
        if (ringSize < 0) {
            ringSize = DEFAULT_RING_SIZE;
        }
        this.ringSize = ringSize;

        final WaitStrategy waitStrategyImpl = selectWaitStrategy(this.waitStrategyName);

        this.disruptor = new Disruptor<Event>(
                EVENT_FACTORY,
                ringSize,
                threadFactory,
                multiProducer ? ProducerType.MULTI : ProducerType.SINGLE,
                waitStrategyImpl);

        if (workerCount == 1) {
            this.disruptor.handleEventsWith(new EventHandler<Event>() {
                @Override
                public void onEvent(Event event, long sequence, boolean endOfBatch) {
                    try {
                        event.process(endOfBatch);
                    } catch (Exception e) {
                        logger.error("Error processing event on executor " + executorIndex, e);
                    } finally {
                        event.reset();
                    }
                }
            });
        } else {
            @SuppressWarnings("unchecked")
            WorkHandler<Event>[] workHandlers = new WorkHandler[workerCount];
            for (int i = 0; i < workerCount; i++) {
                workHandlers[i] = new EventWorkHandler();
            }
            this.disruptor.handleEventsWithWorkerPool(workHandlers);
        }
        this.ringBuffer = this.disruptor.getRingBuffer();

        logger.info("DisruptorEventRouterExecutor[" + executorIndex + "] initialized: ringSize="
                + ringSize + ", workers=" + workerCount + ", strategy=" + this.waitStrategyName
                + ", multiProducer=" + multiProducer);
    }

    /**
     * Backwards-compatible constructor using JVM system properties.
     */
    public DisruptorEventRouterExecutorImpl(boolean collectStats,
            java.util.concurrent.ThreadFactory threadFactory,
            SleeContainer sleeContainer) {
        this(collectStats, threadFactory, sleeContainer,
                Integer.getInteger("jainslee.eventrouter.ringsize", DEFAULT_RING_SIZE),
                1,
                System.getProperty("jainslee.eventrouter.waitstrategy", "blocking"),
                resolveMultiProducer(),
                0);
    }

    /**
     * Backwards-compatible constructor with explicit ring size and worker count.
     */
    public DisruptorEventRouterExecutorImpl(boolean collectStats,
            java.util.concurrent.ThreadFactory threadFactory,
            SleeContainer sleeContainer,
            int ringSize,
            int workerCount) {
        this(collectStats, threadFactory, sleeContainer, ringSize, workerCount,
                System.getProperty("jainslee.eventrouter.waitstrategy", "blocking"),
                resolveMultiProducer(),
                0);
    }

    static boolean resolveMultiProducer() {
        final String jvm = System.getProperty("jainslee.eventrouter.multi.producer");
        if (jvm != null) {
            return Boolean.parseBoolean(jvm);
        }
        return false;
    }

    private static WaitStrategy selectWaitStrategy(String strategy) {
        if ("busyspin".equalsIgnoreCase(strategy)) {
            return new BusySpinWaitStrategy();
        }
        if ("yielding".equalsIgnoreCase(strategy)) {
            return new YieldingWaitStrategy();
        }
        if ("blocking".equalsIgnoreCase(strategy)) {
            return new BlockingWaitStrategy();
        }
        return new BlockingWaitStrategy();
    }

    public void onStart() {
        this.disruptor.start();
    }

    public void onShutdown() {
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
        try {
            Event event = ringBuffer.get(sequenceId);
            event.initMisc(task, stats, null);
        } finally {
            ringBuffer.publish(sequenceId);
        }
    }

    @Override
    public void executeNow(Runnable task) throws InterruptedException, ExecutionException {
        CountDownLatch latch = new CountDownLatch(1);
        long sequenceId = ringBuffer.next();
        try {
            Event event = ringBuffer.get(sequenceId);
            event.initMisc(task, stats, latch);
        } finally {
            ringBuffer.publish(sequenceId);
        }
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
        try {
            Event disruptorEvent = ringBuffer.get(sequenceId);
            disruptorEvent.initRoute(event, sleeContainer, stats, null);
        } finally {
            ringBuffer.publish(sequenceId);
        }
    }

    public long getRingBufferSize() {
        return ringBuffer.getBufferSize();
    }

    public long getRingBufferFillLevel() {
        return ringBuffer.getBufferSize() - ringBuffer.remainingCapacity();
    }

    public double getRingBufferUtilization() {
        long fillLevel = getRingBufferFillLevel();
        return (fillLevel * 100.0) / getRingBufferSize();
    }

    public int getConfiguredRingSize() {
        return ringSize;
    }

    private class EventWorkHandler implements WorkHandler<Event> {
        @Override
        public void onEvent(Event event) throws Exception {
            try {
                event.process(false);
            } catch (Exception e) {
                logger.error("Error processing event on executor " + executorIndex, e);
            } finally {
                event.reset();
            }
        }
    }

    @Override
    public String toString() {
        return "DisruptorEventRouterExecutor[" + executorIndex + ", ringSize=" + getRingBufferSize()
                + ", workers=" + workerCount + ", fillLevel=" + getRingBufferFillLevel() + "]";
    }
}
