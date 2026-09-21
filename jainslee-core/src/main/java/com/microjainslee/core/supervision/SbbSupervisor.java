/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.supervision;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production P3 — local SBB supervisor with OTP-style restart semantics.
 *
 * <p>Triggers (wired from {@code EventRouter}):
 * <ul>
 *   <li><b>Delivery timeout</b> — the per-SBB virtual thread did not finish
 *       {@code onEvent} within the router's delivery timeout. The entity is
 *       wedged: its slot thread is stuck inside user code and no queued
 *       event will ever run. The supervisor force-restarts it (subject to
 *       backoff).</li>
 *   <li><b>Crash loop</b> — {@code maxAttempts} consecutive delivery
 *       exceptions for the same entity id. A successful delivery resets
 *       the failure streak.</li>
 * </ul>
 *
 * <p>Restart = fresh instantiation: the faulty SBB instance is never
 * reused. Total supervised restarts per entity id are capped; when the cap
 * is exceeded the entity is declared DEAD, removed for good, and a
 * CRITICAL alarm is raised via the container {@code AlarmFacility}.
 *
 * <p>M3 — events routed to an entity while its supervised restart is in
 * flight are parked in a bounded replay buffer and re-routed to the fresh
 * instance once the restart lands. Events parked for an entity that goes
 * DEAD (or whose restart is refused) are dropped and counted.
 *
 * <p>All restart work runs on a single dedicated virtual thread, so the
 * router never blocks on supervision.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class SbbSupervisor {

    private static final Logger LOG = LogManager.getLogger(SbbSupervisor.class);

    /** Alarm type raised on the container AlarmFacility when an entity goes DEAD. */
    public static final String ALARM_TYPE = "SbbSupervisor";

    private final SbbSupervisionPort port;
    private final int maxAttempts;
    private final long backoffBaseMs;
    private final long backoffMaxMs;
    private final ConcurrentHashMap<String, EntityAttempts> attempts =
            new ConcurrentHashMap<String, EntityAttempts>();
    private final ExecutorService restartExecutor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final AtomicLong restartsTotal = new AtomicLong();
    private final AtomicLong restartsFailed = new AtomicLong();
    private final AtomicLong deadEntities = new AtomicLong();
    private final AtomicLong timeoutKills = new AtomicLong();

    /** M3 — parked events for entities with a restart in flight. */
    private final EventReplayQueue replayQueue;

    /**
     * @param port          container-side restart mechanics (required)
     * @param maxAttempts   supervised restarts allowed per entity id before
     *                      the entity is declared DEAD ({@code >= 1})
     * @param backoffBaseMs delay before restart #1 ({@code >= 0});
     *                      doubles per restart up to {@code backoffMaxMs}
     * @param backoffMaxMs  backoff ceiling ({@code >= backoffBaseMs})
     */
    public SbbSupervisor(SbbSupervisionPort port, int maxAttempts,
            long backoffBaseMs, long backoffMaxMs) {
        this(port, maxAttempts, backoffBaseMs, backoffMaxMs,
                EventReplayQueue.DEFAULT_PER_ENTITY_CAPACITY);
    }

    /**
     * @param port          container-side restart mechanics (required)
     * @param maxAttempts   supervised restarts allowed per entity id before
     *                      the entity is declared DEAD ({@code >= 1})
     * @param backoffBaseMs delay before restart #1 ({@code >= 0}); doubles
     *                      per restart up to {@code backoffMaxMs}
     * @param backoffMaxMs  backoff ceiling ({@code >= backoffBaseMs})
     * @param replayCapacity per-entity bound of the parked-event replay
     *                      buffer ({@code >= 0}); {@code 0} disables parking
     *                      entirely (pre-M3 behaviour)
     */
    public SbbSupervisor(SbbSupervisionPort port, int maxAttempts,
            long backoffBaseMs, long backoffMaxMs, int replayCapacity) {
        if (port == null) {
            throw new IllegalArgumentException("port is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        if (backoffBaseMs < 0L) {
            throw new IllegalArgumentException("backoffBaseMs must be >= 0, got " + backoffBaseMs);
        }
        if (backoffMaxMs < backoffBaseMs) {
            throw new IllegalArgumentException("backoffMaxMs (" + backoffMaxMs
                    + ") must be >= backoffBaseMs (" + backoffBaseMs + ")");
        }
        if (replayCapacity < 0) {
            throw new IllegalArgumentException(
                    "replayCapacity must be >= 0, got " + replayCapacity);
        }
        this.port = port;
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
        this.backoffMaxMs = backoffMaxMs;
        this.replayQueue = new EventReplayQueue(replayCapacity);
        this.restartExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("sbb-supervisor", 0).factory());
    }

    // ───────────────────────────────────────────────────────────────────
    // Router hooks (cheap, non-blocking)
    // ───────────────────────────────────────────────────────────────────

    /** SBB business exception during delivery — bump the failure streak. */
    public void onSbbException(String sbbId, Exception cause) {
        if (sbbId == null || !running.get()) {
            return;
        }
        EntityAttempts st = attempts.computeIfAbsent(sbbId, EntityAttempts::new);
        if (st.recordFailure() >= maxAttempts) {
            scheduleRestart(sbbId, st, cause);
        }
    }

    /** Per-SBB virtual-thread delivery timeout — the entity is wedged. */
    public void onDeliveryTimeout(String sbbId) {
        if (sbbId == null || !running.get()) {
            return;
        }
        timeoutKills.incrementAndGet();
        EntityAttempts st = attempts.computeIfAbsent(sbbId, EntityAttempts::new);
        st.markWedged();
        scheduleRestart(sbbId, st, null);
    }

    /** Successful delivery — reset the consecutive-failure streak. */
    public void onDeliverySuccess(String sbbId) {
        if (sbbId == null) {
            return;
        }
        EntityAttempts st = attempts.get(sbbId);
        if (st != null) {
            st.clearFailures();
        }
    }

    /**
     * M3 — {@code true} while a supervised restart for {@code sbbId} is
     * scheduled or executing and the entity is not DEAD. The router uses
     * this to decide whether to park an incoming event instead of
     * delivering it to the wedged / doomed entity.
     */
    public boolean isRestarting(String sbbId) {
        if (sbbId == null) {
            return false;
        }
        EntityAttempts st = attempts.get(sbbId);
        return st != null && st.isRestartInFlight();
    }

    /**
     * M3 — park one event for an entity whose supervised restart is in
     * flight. The event is re-routed to the fresh entity once the restart
     * task completes, or dropped when the entity goes DEAD / the restart
     * is refused. Returns {@code false} when the buffer is disabled
     * (capacity 0) or full — the caller then falls back to the normal
     * delivery path.
     */
    public boolean parkEvent(String sbbId, SleeEvent event, ActivityContextInterface aci) {
        if (!running.get()) {
            return false;
        }
        return replayQueue.park(sbbId, event, aci);
    }

    // ───────────────────────────────────────────────────────────────────
    // Restart scheduling (single supervisor thread)
    // ───────────────────────────────────────────────────────────────────

    private void scheduleRestart(String sbbId, EntityAttempts st, Exception cause) {
        if (!st.trySchedule()) {
            return; // dead, or a restart is already in flight
        }
        int restartNo = st.incrementRestarts();
        if (restartNo > maxAttempts) {
            giveUp(sbbId, st, restartNo - 1, cause);
            return;
        }
        long delayMs = backoffDelayMs(restartNo);
        final String why = cause != null
                ? cause.toString() : "delivery timeout (wedged entity thread)";
        LOG.warn("[SbbSupervisor] restart #{} for sbbId={} scheduled in {}ms — {}",
                restartNo, sbbId, delayMs, why);
        try {
            restartExecutor.execute(() -> runRestart(sbbId, st, delayMs));
        } catch (RuntimeException rejected) {
            // Executor shut down between the running check and submit.
            st.clearScheduled();
        }
    }

    private void runRestart(String sbbId, EntityAttempts st, long delayMs) {
        boolean restarted = false;
        try {
            if (delayMs > 0L) {
                Thread.sleep(delayMs);
            }
            if (!running.get()) {
                return;
            }
            if (port.restartEntity(sbbId)) {
                restartsTotal.incrementAndGet();
                st.clearFailures();
                restarted = true;
                LOG.info("[SbbSupervisor] restarted sbbId={} (fresh instance)", sbbId);
            } else {
                restartsFailed.incrementAndGet();
                LOG.warn("[SbbSupervisor] restart refused for sbbId={}", sbbId);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException re) {
            restartsFailed.incrementAndGet();
            LOG.error("[SbbSupervisor] restart threw for sbbId={}: {}",
                    sbbId, re.toString(), re);
        } finally {
            // Clear the in-flight flag BEFORE replaying: while it is set
            // the router parks events for this entity, and the replayed
            // events must dispatch normally against the fresh instance
            // instead of re-parking themselves.
            st.clearScheduled();
            if (restarted) {
                replayParkedEvents(sbbId);
            } else {
                int dropped = replayQueue.abandon(sbbId);
                if (dropped > 0) {
                    LOG.warn("[SbbSupervisor] dropped {} parked event(s) for sbbId={} "
                            + "(restart refused or failed)", dropped, sbbId);
                }
            }
        }
    }

    /**
     * M3 — re-route every event parked for {@code sbbId} while its restart
     * was in flight. Each replayed event goes back through the full router
     * dispatch path, so it gets the transaction / MDC / event-mask
     * machinery, and any failure it causes is booked against the fresh
     * entity exactly like a first-class delivery.
     */
    private void replayParkedEvents(String sbbId) {
        List<EventReplayQueue.ParkedEvent> parked = replayQueue.drain(sbbId);
        for (EventReplayQueue.ParkedEvent parkedEvent : parked) {
            try {
                port.replayEvent(parkedEvent.event(), parkedEvent.aci());
            } catch (RuntimeException re) {
                LOG.error("[SbbSupervisor] replay of {} for sbbId={} threw: {}",
                        parkedEvent.event().getClass().getSimpleName(), sbbId,
                        re.toString(), re);
            }
        }
        if (!parked.isEmpty()) {
            LOG.info("[SbbSupervisor] replayed {} parked event(s) to fresh sbbId={}",
                    parked.size(), sbbId);
        }
    }

    private void giveUp(String sbbId, EntityAttempts st, int attemptsUsed, Exception cause) {
        deadEntities.incrementAndGet();
        st.markDead();
        int dropped = replayQueue.abandon(sbbId);
        final String reason = "gave up after " + attemptsUsed + " supervised restarts"
                + (cause != null ? " (last failure: " + cause + ")" : "")
                + (dropped > 0 ? "; dropped " + dropped + " parked event(s)" : "");
        LOG.error("[SbbSupervisor] sbbId={} DEAD — {}", sbbId, reason);
        try {
            restartExecutor.execute(() -> {
                if (running.get()) {
                    port.giveUp(sbbId, reason);
                }
            });
        } catch (RuntimeException rejected) {
            // Executor shut down; entity stays removed by the router's own
            // exception handling — only the alarm is lost.
        }
    }

    private long backoffDelayMs(int restartNo) {
        // restartNo >= 1 → shift 0,1,2,…; cap the shift to avoid overflow.
        long shift = Math.min(restartNo - 1L, 16L);
        long delay = backoffBaseMs << shift;
        return Math.min(delay, backoffMaxMs);
    }

    // ───────────────────────────────────────────────────────────────────
    // Metrics / lifecycle
    // ───────────────────────────────────────────────────────────────────

    /** Total supervised restarts that produced a fresh entity. Never reset. */
    public long getRestartCount() {
        return restartsTotal.get();
    }

    /** Restarts refused (unknown entity / legacy SBB / not STARTED). */
    public long getFailedRestartCount() {
        return restartsFailed.get();
    }

    /** Entities declared DEAD after exhausting restart attempts. */
    public long getDeadEntityCount() {
        return deadEntities.get();
    }

    /** Delivery timeouts observed (wedged entity threads). */
    public long getTimeoutKillCount() {
        return timeoutKills.get();
    }

    /** M3 — events parked while their entity's supervised restart was in flight. */
    public long getParkedEventCount() {
        return replayQueue.parkedTotal();
    }

    /** M3 — parked events re-routed to a fresh entity after a successful restart. */
    public long getReplayedEventCount() {
        return replayQueue.replayedTotal();
    }

    /** M3 — parked events dropped: DEAD entities, refused restarts, overflow. */
    public long getDroppedEventCount() {
        return replayQueue.droppedTotal();
    }

    /** {@code true} when the supervisor tracks state for {@code sbbId}. */
    public boolean isSupervised(String sbbId) {
        return sbbId != null && attempts.containsKey(sbbId);
    }

    /**
     * Test hook — block until every restart task queued before this call
     * has finished (including its backoff sleep), or the timeout elapses.
     */
    public boolean awaitQuiescence(long timeoutMs) throws InterruptedException {
        try {
            restartExecutor.submit(() -> { }).get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException te) {
            return false;
        } catch (ExecutionException ee) {
            return true;
        }
    }

    /** Stop supervising; in-flight restarts are interrupted and dropped. */
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        replayQueue.clear();
        restartExecutor.shutdownNow();
    }

    /** Per-entity supervision state. */
    private static final class EntityAttempts {
        private final String sbbId;
        private int consecutiveFailures;
        private int restarts;
        private boolean dead;
        private final AtomicBoolean scheduled = new AtomicBoolean(false);

        EntityAttempts(String sbbId) {
            this.sbbId = sbbId;
        }

        synchronized int recordFailure() {
            return ++consecutiveFailures;
        }

        synchronized void clearFailures() {
            consecutiveFailures = 0;
        }

        synchronized void markWedged() {
            // Diagnostic marker: the entity's slot thread was wedged at
            // least once. No behavioural weight beyond the metrics above.
        }

        synchronized int incrementRestarts() {
            return ++restarts;
        }

        synchronized void markDead() {
            dead = true;
        }

        synchronized boolean isDead() {
            return dead;
        }

        boolean trySchedule() {
            return !isDead() && scheduled.compareAndSet(false, true);
        }

        void clearScheduled() {
            scheduled.set(false);
        }

        boolean isRestartInFlight() {
            return !isDead() && scheduled.get();
        }
    }
}
