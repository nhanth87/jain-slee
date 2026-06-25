package com.microjainslee.core;

import java.util.concurrent.*;

/**
 * Hierarchical Timing Wheel for efficient timer management.
 */
public class HierarchicalTimingWheel {
    private final ScheduledExecutorService executor;

    public HierarchicalTimingWheel() {
        this.executor = Executors.newScheduledThreadPool(4);
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return executor.schedule(task, delay, unit);
    }

    public void shutdown() {
        executor.shutdown();
    }
}