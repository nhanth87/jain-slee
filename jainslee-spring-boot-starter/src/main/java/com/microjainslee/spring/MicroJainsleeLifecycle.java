/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.spring;

import com.microjainslee.core.MicroSleeContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.SmartLifecycle;

public class MicroJainsleeLifecycle implements SmartLifecycle {

    private static final Logger LOG = LogManager.getLogger(MicroJainsleeLifecycle.class);

    private final MicroSleeContainer container;
    private volatile boolean running = false;

    public MicroJainsleeLifecycle(MicroSleeContainer container) {
        this.container = container;
        LOG.debug("MicroJainsleeLifecycle created (phase={}, container={})",
                Integer.MIN_VALUE + 100, container.getState());
    }

    @Override public boolean isAutoStartup() { return true; }

    @Override
    public void start() {
        LOG.info("SmartLifecycle.start() — starting MicroSleeContainer (current state={})", container.getState());
        container.start();
        running = true;
        LOG.info("MicroSleeContainer started via SmartLifecycle (phase={})", getPhase());
    }

    @Override
    public void stop() {
        LOG.info("SmartLifecycle.stop() — stopping MicroSleeContainer (current state={})", container.getState());
        try {
            container.stop();
            running = false;
            LOG.info("MicroSleeContainer stopped via SmartLifecycle");
        } catch (Throwable t) {
            LOG.error("MicroSleeContainer.stop() failed: {}", t.getMessage(), t);
            throw t;
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MIN_VALUE + 100; }
}