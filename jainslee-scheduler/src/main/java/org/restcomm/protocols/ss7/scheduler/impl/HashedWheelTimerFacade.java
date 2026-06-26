/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Vendored from jSS7 scheduler (RestComm/jss7 9.5.0).
 * Original package: org.restcomm.protocols.ss7.scheduler
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */


package org.restcomm.protocols.ss7.scheduler.impl;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Thin facade over Netty {@link HashedWheelTimer} for protocol timer scheduling.
 */
public class HashedWheelTimerFacade {

    private final HashedWheelTimer timer;

    public HashedWheelTimerFacade() {
        this(100L, TimeUnit.MILLISECONDS);
    }

    public HashedWheelTimerFacade(long tickDuration, TimeUnit unit) {
        this(new DefaultThreadFactory("protocol-timer"), tickDuration, unit);
    }

    public HashedWheelTimerFacade(ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this.timer = new HashedWheelTimer(threadFactory, tickDuration, unit);
    }

    public void start() {
        timer.start();
    }

    public Timeout schedule(Runnable task, long delay, TimeUnit unit) {
        return timer.newTimeout(new RunnableTimerTask(task), delay, unit);
    }

    public void stop() {
        timer.stop();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            if (timer.pendingTimeouts() == 0) {
                Thread.sleep(20L);
                return true;
            }
            Thread.sleep(10L);
        }
        return timer.pendingTimeouts() == 0;
    }

    private static final class RunnableTimerTask implements TimerTask {

        private final Runnable task;

        private RunnableTimerTask(Runnable task) {
            this.task = task;
        }

        @Override
        public void run(Timeout timeout) throws Exception {
            task.run();
        }
    }
}
