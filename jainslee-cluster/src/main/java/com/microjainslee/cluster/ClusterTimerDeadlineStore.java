/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import com.microjainslee.core.TimerDeadlineStore;

import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.partitionhandling.AvailabilityException;

/**
 * ADR 0007 D6 — cluster-replicated {@link TimerDeadlineStore}.
 *
 * <p>Absolute deadlines (epoch ms) on cache {@code slee-timer-deadlines}
 * ({@code REPL_SYNC} in cluster mode, {@code LOCAL} otherwise — same pattern
 * as {@link Ss7DialogClusterCaches}). A survivor re-arms a local timer from
 * the replicated deadline after takeover; firing is still guarded by the
 * dialog lease ({@code RaDialogLeaseCaches::mayTransmit} via the bridge
 * transmit guard), so only the owner delivers.
 *
 * <p>Values are {@code com.microjainslee.*} records — allow-list clean.
 */
public final class ClusterTimerDeadlineStore implements TimerDeadlineStore {

    private static final Logger LOG = LogManager.getLogger(ClusterTimerDeadlineStore.class);

    public static final String CACHE_NAME = "slee-timer-deadlines";

    private final Cache<Long, TimerDeadline> cache;

    public ClusterTimerDeadlineStore(ClusterManager clusterManager) {
        Objects.requireNonNull(clusterManager, "clusterManager");
        CacheMode mode = clusterManager.isClusterMode() ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
        this.cache = clusterManager.getCache(CACHE_NAME, mode);
    }

    @Override
    public void put(TimerDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        try {
            cache.put(deadline.timerId(), deadline);
        } catch (AvailabilityException e) {
            LOG.warn("timer deadline put({}) skipped: cache unavailable (partition minority?)",
                    deadline.timerId());
        }
    }

    @Override
    public void remove(long timerId) {
        try {
            cache.remove(timerId);
        } catch (AvailabilityException e) {
            LOG.debug("timer deadline remove({}) deferred: cache unavailable", timerId);
        }
    }

    @Override
    public TimerDeadline get(long timerId) {
        try {
            return cache.get(timerId);
        } catch (AvailabilityException e) {
            return null;
        }
    }

    @Override
    public int size() {
        try {
            return cache.size();
        } catch (AvailabilityException e) {
            return 0;
        }
    }
}
