/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.recovery;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microjainslee.api.SleeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sprint S7 - default {@link SessionRecoveryService} implementation backed
 * by a synchronized {@link LinkedHashMap} in access-order with
 * {@code removeEldestEntry} eviction <strong>and</strong> TTL reclaim
 * (PolyVoice N-N / micro-jainslee zombie pattern: idle snapshots expire).
 *
 * <h2>R10 anti-loop guard</h2>
 * {@link #REHYDRATING} is a process-wide {@link ThreadLocal} flag set for
 * the duration of a {@link #tryRehydrateAndDeliver} call. A second
 * rehydration attempt on the same thread returns {@code false} immediately.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class SessionRecoveryServiceImpl implements SessionRecoveryService {

    private static final Logger LOG = LogManager.getLogger(SessionRecoveryServiceImpl.class);

    /**
     * R10 anti-loop guard. {@code true} while a rehydration call is in
     * progress on the current thread; a nested call short-circuits.
     */
    private static final ThreadLocal<Boolean> REHYDRATING =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final int maxSize;
    private final long ttlMs;
    private final RehydrateCallback callback;
    private final Map<String, RecoverySnapshot> snapshots;
    private final AtomicLong reclaimedExpired = new AtomicLong();
    private final AtomicLong rejectedStale = new AtomicLong();

    /** Default constructor - {@link #DEFAULT_MAX_SNAPSHOTS} + {@link #DEFAULT_TTL_MS}. */
    public SessionRecoveryServiceImpl(RehydrateCallback callback) {
        this(DEFAULT_MAX_SNAPSHOTS, DEFAULT_TTL_MS, callback);
    }

    /** Capacity-only constructor (keeps default TTL). */
    public SessionRecoveryServiceImpl(int maxSize, RehydrateCallback callback) {
        this(maxSize, DEFAULT_TTL_MS, callback);
    }

    public SessionRecoveryServiceImpl(int maxSize, long ttlMs, RehydrateCallback callback) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1, got " + maxSize);
        }
        if (ttlMs < 1L) {
            throw new IllegalArgumentException("ttlMs must be >= 1, got " + ttlMs);
        }
        this.maxSize = maxSize;
        this.ttlMs = ttlMs;
        this.callback = callback;
        final int initialCapacity = (int) Math.min(maxSize, 1024L);
        LinkedHashMap<String, RecoverySnapshot> lru = new LinkedHashMap<String, RecoverySnapshot>(
                initialCapacity, 0.75f, /* accessOrder= */ true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RecoverySnapshot> eldest) {
                return size() > SessionRecoveryServiceImpl.this.maxSize;
            }
        };
        this.snapshots = Collections.synchronizedMap(lru);
    }

    @Override
    public void registerSnapshot(RecoverySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        if (snapshot.entityId() == null) {
            throw new IllegalArgumentException("snapshot.entityId is required");
        }
        reclaimExpired();
        long now = System.currentTimeMillis();
        if (isExpired(snapshot, now)) {
            reclaimedExpired.incrementAndGet();
            LOG.debug("[SessionRecoveryService] refuse expired snapshot entityId={} age={}ms",
                    snapshot.entityId(), now - snapshot.capturedAtMs());
            return;
        }
        snapshots.put(snapshot.entityId(), snapshot);
    }

    @Override
    public Optional<RecoverySnapshot> getSnapshot(String entityId) {
        if (entityId == null) {
            return Optional.empty();
        }
        reclaimExpired();
        RecoverySnapshot snap = snapshots.get(entityId);
        if (snap == null) {
            return Optional.empty();
        }
        if (isExpired(snap, System.currentTimeMillis())) {
            snapshots.remove(entityId, snap);
            reclaimedExpired.incrementAndGet();
            return Optional.empty();
        }
        return Optional.of(snap);
    }

    @Override
    public Optional<RecoverySnapshot> consumeSnapshot(String entityId) {
        if (entityId == null) {
            return Optional.empty();
        }
        reclaimExpired();
        RecoverySnapshot snap = snapshots.remove(entityId);
        if (snap == null) {
            return Optional.empty();
        }
        if (isExpired(snap, System.currentTimeMillis())) {
            reclaimedExpired.incrementAndGet();
            return Optional.empty();
        }
        return Optional.of(snap);
    }

    @Override
    public boolean tryRehydrateAndDeliver(String sbbId, Object event,
            com.microjainslee.api.ActivityContextInterface aci,
            com.microjainslee.api.SleeEventHandler handler) {
        // R10 - short-circuit if we are already rehydrating on this thread.
        if (REHYDRATING.get().booleanValue()) {
            LOG.debug("[SessionRecoveryService] re-entry blocked on sbbId={} (R10)", sbbId);
            return false;
        }
        if (sbbId == null || event == null || handler == null) {
            return false;
        }
        RecoverySnapshot snap = snapshots.remove(sbbId);
        if (snap == null) {
            reclaimExpired();
            return false;
        }
        long now = System.currentTimeMillis();
        if (isExpired(snap, now)) {
            rejectedStale.incrementAndGet();
            LOG.warn("[SessionRecoveryService] stale snapshot for sbbId={} age={}ms ttlMs={} — drop",
                    sbbId, now - snap.capturedAtMs(), ttlMs);
            return false;
        }
        if (callback == null) {
            LOG.warn("[SessionRecoveryService] snapshot found for sbbId={} but no "
                    + "RehydrateCallback is wired - dropping event", sbbId);
            return false;
        }
        REHYDRATING.set(Boolean.TRUE);
        try {
            boolean reconstructed = callback.reconstructFromSnapshot(snap);
            if (!reconstructed) {
                LOG.warn("[SessionRecoveryService] reconstruction failed for sbbId={} "
                        + "(age={}ms) - dropping event",
                        sbbId, now - snap.capturedAtMs());
                return false;
            }
            try {
                handler.onEvent((SleeEvent) event, aci);
            } catch (Exception ex) {
                LOG.error("[SessionRecoveryService] handler.onEvent threw for sbbId={}: {}",
                        sbbId, ex.getMessage(), ex);
                return false;
            }
            LOG.info("[SessionRecoveryService] rehydrated sbbId={} age={}ms class={}",
                    sbbId,
                    now - snap.capturedAtMs(),
                    snap.sbbClassFqn());
            return true;
        } catch (RuntimeException re) {
            LOG.error("[SessionRecoveryService] rehydration threw for sbbId={}: {}",
                    sbbId, re.getMessage(), re);
            return false;
        } finally {
            // ALWAYS clear the flag, even when reconstruction failed or threw.
            REHYDRATING.remove();
        }
    }

    @Override
    public int activeSnapshotCount() {
        reclaimExpired();
        return snapshots.size();
    }

    @Override
    public int reclaimExpired() {
        long now = System.currentTimeMillis();
        List<String> doomed = new ArrayList<>();
        synchronized (snapshots) {
            for (Map.Entry<String, RecoverySnapshot> e : snapshots.entrySet()) {
                if (isExpired(e.getValue(), now)) {
                    doomed.add(e.getKey());
                }
            }
            for (String id : doomed) {
                snapshots.remove(id);
            }
        }
        if (!doomed.isEmpty()) {
            reclaimedExpired.addAndGet(doomed.size());
            LOG.debug("[SessionRecoveryService] reclaimed {} expired snapshots ttlMs={}",
                    doomed.size(), ttlMs);
        }
        return doomed.size();
    }

    /** Test/diagnostics — configured cap. */
    public int maxSize() {
        return maxSize;
    }

    /** Test/diagnostics — snapshot TTL window. */
    public long ttlMs() {
        return ttlMs;
    }

    /** Test/diagnostics — cumulative TTL reclaim count. */
    public long reclaimedExpiredCount() {
        return reclaimedExpired.get();
    }

    /** Test/diagnostics — rehydrate attempts rejected as stale. */
    public long rejectedStaleCount() {
        return rejectedStale.get();
    }

    private boolean isExpired(RecoverySnapshot snap, long nowMs) {
        return (nowMs - snap.capturedAtMs()) > ttlMs;
    }

    /**
     * Test-only - peek at the {@code REHYDRATING} guard. Production code
     * should treat the flag as an implementation detail.
     */
    static boolean isRehydrating() {
        return REHYDRATING.get().booleanValue();
    }

    /**
     * Test-only - reset the {@code REHYDRATING} guard on the current
     * thread.
     */
    static void resetRehydratingFlag() {
        REHYDRATING.remove();
    }
}
