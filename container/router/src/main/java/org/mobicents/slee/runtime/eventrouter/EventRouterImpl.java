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

import org.apache.log4j.Logger;
import org.mobicents.slee.container.AbstractSleeContainerModule;
import org.mobicents.slee.container.eventrouter.EventRouter;
import org.mobicents.slee.container.eventrouter.EventRouterExecutor;
import org.mobicents.slee.container.eventrouter.EventRouterExecutorMapper;
import org.mobicents.slee.container.eventrouter.stats.EventRouterStatistics;
import org.mobicents.slee.container.management.jmx.EventRouterConfiguration;
import org.mobicents.slee.runtime.eventrouter.stats.EventRouterStatisticsImpl;
import org.mobicents.slee.util.concurrent.SleeThreadFactory;

/**
 * HIGH PERFORMANCE Event Router using LMAX Disruptor.
 * Optimized for modern hardware with 16-32 CPU cores and 16-64GB RAM.
 *
 * Parallelism model: N Disruptor executors (one per CPU pipeline), each with
 * a single worker thread. Activities are pinned to an executor via
 * ActivityHashingEventRouterExecutorMapper at creation time.
 *
 * @author Performance Optimization Team
 * @author Original: Eduardo Martins
 */
public class EventRouterImpl extends AbstractSleeContainerModule implements EventRouter {

    private static final Logger logger = Logger.getLogger(EventRouter.class);

    private static final int DEFAULT_RING_SIZE = 262144;
    private static final String DEFAULT_WAIT_STRATEGY = "blocking";

    private EventRouterExecutor[] executors;
    private EventRouterExecutorMapper executorMapper;
    private EventRouterStatistics statistics;
    private final EventRouterConfiguration configuration;

    private boolean useDisruptor;
    private int eventRouterThreads;
    private int ringSize;
    private String waitStrategy;
    private boolean multiProducer;

    public EventRouterImpl(EventRouterConfiguration configuration) {
        this.configuration = configuration;
    }

    private void resolveConfiguration() {
        final String jvmDisruptor = System.getProperty("jainslee.eventrouter.useDisruptor");
        if (jvmDisruptor != null) {
            useDisruptor = Boolean.parseBoolean(jvmDisruptor);
        } else {
            final String configDisruptor = configuration.getProperty("useDisruptor");
            useDisruptor = configDisruptor == null || Boolean.parseBoolean(configDisruptor);
        }

        eventRouterThreads = configuration.getEventRouterThreads();
        if (eventRouterThreads <= 0) {
            eventRouterThreads = Integer.getInteger(
                    "jainslee.eventrouter.threads",
                    Math.max(4, Runtime.getRuntime().availableProcessors()));
        }

        final String configRingSize = configuration.getProperty("ringsize");
        if (configRingSize != null) {
            ringSize = Integer.parseInt(configRingSize);
        } else {
            ringSize = Integer.getInteger("jainslee.eventrouter.ringsize", DEFAULT_RING_SIZE);
        }

        final String jvmWaitStrategy = System.getProperty("jainslee.eventrouter.waitstrategy");
        if (jvmWaitStrategy != null) {
            waitStrategy = jvmWaitStrategy;
        } else {
            final String configWaitStrategy = configuration.getProperty("waitstrategy");
            waitStrategy = configWaitStrategy != null ? configWaitStrategy : DEFAULT_WAIT_STRATEGY;
        }

        final String jvmMultiProducer = System.getProperty("jainslee.eventrouter.multi.producer");
        if (jvmMultiProducer != null) {
            multiProducer = Boolean.parseBoolean(jvmMultiProducer);
        } else {
            final String configMultiProducer = configuration.getProperty("multi.producer");
            multiProducer = configMultiProducer == null || Boolean.parseBoolean(configMultiProducer);
        }
    }

    @Override
    public void sleeInitialization() {
        resolveConfiguration();
        logger.info("Mobicents JAIN SLEE Event Router initialized. Using "
                + (useDisruptor ? "LMAX Disruptor" : "standard ThreadPool")
                + " with " + eventRouterThreads + " executors, ring size "
                + ringSize + ", wait strategy " + waitStrategy
                + ", multiProducer=" + multiProducer);
    }

    @Override
    public void sleeStarting() {
        resolveConfiguration();

        if (this.executors != null) {
            for (EventRouterExecutor executor : this.executors) {
                executor.shutdown();
            }
        }

        if (useDisruptor) {
            createDisruptorExecutors();
        } else {
            createStandardExecutors();
        }

        try {
            Class<?> executorMapperClass = Class.forName(configuration.getExecutorMapperClassName());
            executorMapper = (EventRouterExecutorMapper) executorMapperClass.newInstance();
            executorMapper.setExecutors(executors);
        } catch (Throwable e) {
            throw new IllegalStateException("Unable to create event router executor mapper class instance", e);
        }

        statistics = new EventRouterStatisticsImpl(this);
    }

    private void createDisruptorExecutors() {
        logger.info("Creating " + eventRouterThreads + " Disruptor-based event router executors");

        this.executors = new EventRouterExecutor[eventRouterThreads];

        for (int i = 0; i < eventRouterThreads; i++) {
            SleeThreadFactory threadFactory = new SleeThreadFactory("SLEE-Disruptor-ER-" + i + "-");
            this.executors[i] = new DisruptorEventRouterExecutorImpl(
                    configuration.isCollectStats(),
                    threadFactory,
                    sleeContainer,
                    ringSize,
                    1,
                    waitStrategy,
                    multiProducer,
                    i);
            ((DisruptorEventRouterExecutorImpl) this.executors[i]).onStart();
        }

        logger.info("Disruptor-based event router executors created successfully");
    }

    private void createStandardExecutors() {
        logger.info("Creating " + eventRouterThreads + " standard event router executors");

        this.executors = new EventRouterExecutor[eventRouterThreads];
        for (int i = 0; i < eventRouterThreads; i++) {
            this.executors[i] = new EventRouterExecutorImpl(
                    configuration.isCollectStats(),
                    new SleeThreadFactory("SLEE-EventRouterExecutor-" + i),
                    sleeContainer);
        }
    }

    @Override
    public String toString() {
        return "EventRouter: "
                + "\n+-- Executors: " + (executors != null ? executors.length : 0)
                + "\n+-- Type: " + (useDisruptor ? "LMAX Disruptor" : "ThreadPool")
                + "\n+-- Threads: " + eventRouterThreads
                + "\n+-- Ring Size: " + ringSize
                + "\n+-- Wait Strategy: " + waitStrategy;
    }

    public EventRouterStatistics getEventRouterStatistics() {
        return statistics;
    }

    public EventRouterExecutor[] getExecutors() {
        return executors;
    }

    public EventRouterExecutorMapper getEventRouterExecutorMapper() {
        return executorMapper;
    }

    public EventRouterConfiguration getConfiguration() {
        return configuration;
    }

}
