/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Vendored from jSS7 scheduler (RestComm/jSS7 9.5.0).
 * Original package: org.restcomm.protocols.ss7.scheduler
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */


package org.restcomm.protocols.ss7.scheduler.impl;

import org.agrona.DeadlineTimerWheel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Agrona-backed {@link DeadlineTimerWheel} facade replacing Netty
 * {@code HashedWheelTimer} with deadline scheduling at ~1 ms tick resolution.
 *
 * <h3>Timeout strategy</h3>
 * <ul>
 *   <li>Deadlines are absolute {@link System#nanoTime()} values; the wheel
 *       quantizes them to {@code TICK_NANOS} = 2^20 ns ≈ 1.049 ms (Agrona
 *       requires a power-of-2 tick), so worst-case firing error is one tick
 *       plus OS wake-up latency.</li>
 *   <li>A dedicated <em>platform</em> daemon thread drives the wheel. It
 *       parks only until the next tick boundary
 *       ({@link DeadlineTimerWheel#currentTickTime()}) instead of a fixed
 *       1 ms sleep, so it never oversleeps a due tick and busy-catches-up
 *       without parking when it falls behind.</li>
 *   <li>Expired callbacks are collected under the wheel lock but executed
 *       <em>outside</em> it, so a slow callback can never block concurrent
 *       {@code schedule()}/{@code cancel()} calls. Callbacks run on the wheel
 *       thread and must be cheap and non-blocking — SLEE consumers re-post to
 *       the EventRouter (SBB code never runs on the wheel thread).</li>
 *   <li>Timers may expire in the same tick out of order (Agrona caveat);
 *       ordering within one tick is not guaranteed.</li>
 * </ul>
 *
 * <p>Thread-safety: {@link DeadlineTimerWheel} itself is not thread safe, so
 * a {@link ReentrantLock} serializes all wheel mutations. Callback-triggered
 * (reentrant) schedules are safe because they run outside the lock.
 */
public final class AgronaTimerWheelFacade {

    private static final Logger LOG = LogManager.getLogger(AgronaTimerWheelFacade.class);

    /**
     * Tick resolution in nanoseconds. Agrona's {@link DeadlineTimerWheel}
     * requires this to be a power of 2, so we use 2^20 ns ≈ 1.049 ms — the
     * power-of-2 nanosecond value closest to a 1 ms tick.
     */
    private static final long TICK_NANOS = 1L << 20; // 1_048_576 ns ≈ 1.049 ms

    /** Number of ticks per wheel revolution — must be a power of 2. */
    private static final int WHEEL_SIZE = 512;

    /** Max expiries drained per lock acquisition, bounding lock hold time. */
    private static final int EXPIRY_LIMIT_PER_POLL = 256;

    private final DeadlineTimerWheel wheel;
    private final ConcurrentHashMap<Long, Runnable> tasks;
    private final ReentrantLock wheelLock;
    private final AtomicBoolean running;
    private final AtomicLong firedCount = new AtomicLong();
    private final AtomicLong cancelledCount = new AtomicLong();
    private volatile Thread pollThread;

    public AgronaTimerWheelFacade() {
        // Agrona: DeadlineTimerWheel(timeUnit, startTime, tickResolution, ticksPerWheel).
        // startTime is the wheel's epoch (absolute nanoTime) — schedule/poll below
        // both use absolute System.nanoTime(); tickResolution is TICK_NANOS (~1 ms),
        // ticksPerWheel is WHEEL_SIZE (a power of 2).
        this.wheel = new DeadlineTimerWheel(
                TimeUnit.NANOSECONDS, System.nanoTime(), TICK_NANOS, WHEEL_SIZE);
        this.tasks = new ConcurrentHashMap<>();
        this.wheelLock = new ReentrantLock();
        this.running = new AtomicBoolean(false);
    }

    /**
     * Starts the background polling thread. A platform daemon thread is used
     * (not a virtual thread): the loop is a single long-lived timing-critical
     * spinner and platform-thread {@link LockSupport#parkNanos(long)} gives
     * the tightest wake-up latency.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            pollThread = Thread.ofPlatform()
                    .daemon()
                    .name("agrona-timer-wheel")
                    .start(this::pollLoop);
            LOG.info("AgronaTimerWheelFacade started (tick={}ns, slots={})",
                    TICK_NANOS, WHEEL_SIZE);
        }
    }

    /**
     * Schedules a task to run after the given delay.
     *
     * @param task  the callback to fire when the deadline expires
     * @param delay the delay magnitude
     * @param unit  the time unit of {@code delay}
     * @return a {@link Runnable} that cancels the scheduled timer when invoked
     */
    public Runnable schedule(Runnable task, long delay, TimeUnit unit) {
        long nowNs = System.nanoTime();
        long deadlineNs = nowNs + unit.toNanos(delay);
        wheelLock.lock();
        try {
            // After a long idle period currentTick lags real time; winding it
            // forward is only safe with no active timers (Agrona contract) and
            // avoids a poll-per-tick catch-up burst for the new timer.
            if (wheel.timerCount() == 0) {
                wheel.currentTickTime(nowNs);
            }
            long timerId = wheel.scheduleTimer(deadlineNs);
            tasks.put(timerId, task);
            return () -> {
                wheelLock.lock();
                try {
                    // Agrona reuses slot-based timerIds after fire/cancel, so a
                    // late cancel must only act if the mapping still points to
                    // *this* schedule's task — otherwise it would cancel an
                    // unrelated newer timer that inherited the same id.
                    if (tasks.remove(timerId, task)) {
                        wheel.cancelTimer(timerId);
                        cancelledCount.incrementAndGet();
                    }
                } finally {
                    wheelLock.unlock();
                }
            };
        } finally {
            wheelLock.unlock();
        }
    }

    /** Number of timers scheduled but not yet fired or cancelled. */
    public int pendingTimers() {
        return tasks.size();
    }

    /** Total timers fired since start. */
    public long firedTimers() {
        return firedCount.get();
    }

    /** Total timers cancelled since start. */
    public long cancelledTimers() {
        return cancelledCount.get();
    }

    /**
     * Stops the polling thread and discards all pending timers.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            Thread t = pollThread;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(TimeUnit.SECONDS.toMillis(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                pollThread = null;
            }
            wheelLock.lock();
            try {
                wheel.clear();
                tasks.clear();
            } finally {
                wheelLock.unlock();
            }
            LOG.info("AgronaTimerWheelFacade stopped (fired={}, cancelled={})",
                    firedCount.get(), cancelledCount.get());
        }
    }

    /**
     * Blocks until no pending timers remain or the timeout elapses.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of {@code timeout}
     * @return {@code true} if no pending timers remain; {@code false} on timeout
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNs = System.nanoTime() + unit.toNanos(timeout);
        while (!tasks.isEmpty() && System.nanoTime() < deadlineNs) {
            Thread.sleep(5L);
        }
        return tasks.isEmpty();
    }

    // ─── internal ────────────────────────────────────────────────────────

    /**
     * Poll loop. Per iteration: drain up to {@link #EXPIRY_LIMIT_PER_POLL}
     * expired timers under the lock into a local batch, release the lock, run
     * the batch, then park until the next tick boundary only if nothing was
     * due. {@code poll()} advances at most one tick per call, so when behind
     * schedule the loop iterates without parking until caught up.
     */
    private void pollLoop() {
        final List<Runnable> due = new ArrayList<>(EXPIRY_LIMIT_PER_POLL);
        final DeadlineTimerWheel.TimerHandler collector = (timeUnit, now, timerId) -> {
            Runnable task = tasks.remove(timerId);
            if (task != null) {
                due.add(task);
            }
            return true;
        };

        while (running.get()) {
            long nowNs = System.nanoTime();
            long parkNs = 0L;
            wheelLock.lock();
            try {
                wheel.poll(nowNs, collector, EXPIRY_LIMIT_PER_POLL);
                long nextTickNs = wheel.currentTickTime();
                if (due.isEmpty() && nowNs < nextTickNs) {
                    parkNs = Math.min(nextTickNs - nowNs, TICK_NANOS);
                }
            } finally {
                wheelLock.unlock();
            }

            if (!due.isEmpty()) {
                for (Runnable task : due) {
                    try {
                        task.run();
                    } catch (Exception e) {
                        LOG.error("Timer task failed", e);
                    }
                }
                firedCount.addAndGet(due.size());
                due.clear();
                continue; // more timers may already be due — re-poll immediately
            }

            if (parkNs > 0L) {
                LockSupport.parkNanos(parkNs);
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
    }
}
