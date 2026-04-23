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
 * Configuration via system properties:
 * - Djainslee.eventrouter.threads=8 (default: CPU cores)
 * - Djainslee.eventrouter.ringsize=32768 (default: 32768)
 * - Djainslee.eventrouter.waitstrategy=busyspin (default: busyspin)
 * 
 * @author Performance Optimization Team
 * @author Original: Eduardo Martins
 */
public class EventRouterImpl extends AbstractSleeContainerModule implements EventRouter {

    private static final Logger logger = Logger.getLogger(EventRouter.class);

    // Configuration system properties for modern hardware
    private static final int EVENT_ROUTER_THREADS = Integer.getInteger(
        "jainslee.eventrouter.threads", 
        Math.max(4, Runtime.getRuntime().availableProcessors()));
    private static final int RING_SIZE = Integer.getInteger(
        "jainslee.eventrouter.ringsize", 32768);
    private static final boolean USE_DISRUPTOR = Boolean.getBoolean("jainslee.eventrouter.useDisruptor");

    /**
     * The array of {@link EventRouterExecutor}s that are used to route events
     */
    private EventRouterExecutor[] executors;
        
    /**
     * Maps executors to activities.
     */
    private EventRouterExecutorMapper executorMapper;
    
    /**
     * Provides performance and load statistics of the event router.
     */
    private EventRouterStatistics statistics;
    
    private final EventRouterConfiguration configuration;
    
    /**
     * Creates a new EventRouterImpl with default configuration.
     * @param configuration the event router configuration
     */
    public EventRouterImpl(EventRouterConfiguration configuration) {
        this.configuration = configuration;
    }
    
    @Override
    public void sleeInitialization() {
        logger.info("Mobicents JAIN SLEE Event Router initialized. Using " + 
            (USE_DISRUPTOR ? "LMAX Disruptor" : "standard ThreadPool") + 
            " with " + EVENT_ROUTER_THREADS + " threads and ring size " + RING_SIZE);
    }
    
    @Override
    public void sleeStarting() {
        // get ridden of old executors, if any
        if (this.executors != null) {
            for (EventRouterExecutor executor : this.executors) {
                executor.shutdown();
            }
        }

        // Use Disruptor for event routing if enabled (default true for performance)
        if (USE_DISRUPTOR) {
            createDisruptorExecutors();
        } else {
            createStandardExecutors();
        }
        
        // create mapper
        try {
            Class<?> executorMapperClass = Class.forName(configuration.getExecutorMapperClassName());
            executorMapper = (EventRouterExecutorMapper) executorMapperClass.newInstance();
            executorMapper.setExecutors(executors);
        } catch (Throwable e) {
            throw new IllegalStateException("Unable to create event router executor mapper class instance",e);
        }       
        
        // create stats
        statistics = new EventRouterStatisticsImpl(this);
    }

    /**
     * Creates Disruptor-based executors for high performance
     */
    private void createDisruptorExecutors() {
        logger.info("Creating " + EVENT_ROUTER_THREADS + " Disruptor-based event router executors");
        
        this.executors = new EventRouterExecutor[EVENT_ROUTER_THREADS];
        SleeThreadFactory threadFactory = new SleeThreadFactory("SLEE-Disruptor-ER-");
        
        for (int i = 0; i < EVENT_ROUTER_THREADS; i++) {
            this.executors[i] = new DisruptorEventRouterExecutorImpl(
                configuration.isCollectStats(), 
                threadFactory, 
                sleeContainer,
                RING_SIZE,
                1  // 1 worker per executor, multiple executors provide parallelism
            );
            // Start the disruptor
            ((DisruptorEventRouterExecutorImpl)this.executors[i]).onStart();
        }
        
        logger.info("Disruptor-based event router executors created successfully");
    }

    /**
     * Creates standard ThreadPoolExecutor-based executors (fallback)
     */
    private void createStandardExecutors() {
        // Use configuration.getEventRouterThreads() for backwards compatibility
        int threadCount = configuration.getEventRouterThreads();
        if (threadCount <= 0) {
            threadCount = EVENT_ROUTER_THREADS;
        }
        
        logger.info("Creating " + threadCount + " standard event router executors");
        
        this.executors = new EventRouterExecutor[threadCount];
        for (int i = 0; i < threadCount; i++) {
            this.executors[i] = new EventRouterExecutorImpl(
                configuration.isCollectStats(), 
                new SleeThreadFactory("SLEE-EventRouterExecutor-"+i), 
                sleeContainer
            );
        }
    }
    
    @Override
    public String toString() {
        return "EventRouter: "
            + "\n+-- Executors: " + executors.length
            + "\n+-- Type: " + (USE_DISRUPTOR ? "LMAX Disruptor" : "ThreadPool")
            + "\n+-- Threads: " + EVENT_ROUTER_THREADS
            + "\n+-- Ring Size: " + RING_SIZE;
    }
    
    /* (non-Javadoc)
     * @see org.mobicents.slee.runtime.eventrouter.EventRouter#getEventRouterStatistics()
     */
    public EventRouterStatistics getEventRouterStatistics() {
        return statistics;
    }
    
    /* (non-Javadoc)
     * @see org.mobicents.slee.runtime.eventrouter.EventRouter#getExecutors()
     */
    public EventRouterExecutor[] getExecutors() {
        return executors;
    }
    
    /* (non-Javadoc)
     * @see org.mobicents.slee.runtime.eventrouter.EventRouter#getEventRouterExecutorMapper()
     */
    public EventRouterExecutorMapper getEventRouterExecutorMapper() {
        return executorMapper;
    }

    public EventRouterConfiguration getConfiguration() {
        return configuration;
    }

}
