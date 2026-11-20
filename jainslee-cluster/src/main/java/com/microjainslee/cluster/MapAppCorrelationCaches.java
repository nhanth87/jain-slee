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

import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.partitionhandling.AvailabilityException;

/**
 * Pending-issues #3 — app correlation id → OTID, replicated.
 *
 * <p>The MAP helpers' {@code appDialogId ↔ OTID} maps (ATI /
 * sendRoutingInfoForRoamingNumber, USSD menus) are node-local. After a
 * takeover the survivor must publish on the app id, not the OTID, so the
 * mapping is written next to the TCAP snapshot ({@code map-app-correlation},
 * {@code REPL_SYNC} in cluster mode, {@code LOCAL} otherwise) at export time
 * and re-registered on import. Single-op flows carry no correlation id and are
 * unaffected ({@code null} is never stored).
 *
 * <p>Values are plain decimal OTID strings — allow-list clean.
 */
public final class MapAppCorrelationCaches {

    private static final Logger LOG = LogManager.getLogger(MapAppCorrelationCaches.class);

    public static final String CACHE_NAME = "map-app-correlation";

    private final Cache<String, String> cache;

    public MapAppCorrelationCaches(ClusterManager clusterManager) {
        Objects.requireNonNull(clusterManager, "clusterManager");
        CacheMode mode = clusterManager.isClusterMode() ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
        this.cache = clusterManager.getCache(CACHE_NAME, mode);
    }

    /** Record {@code appCorrelationId → otid}. No-op for null/blank ids. */
    public void register(String appCorrelationId, long localOtid) {
        if (appCorrelationId == null || appCorrelationId.isBlank() || localOtid <= 0) {
            return;
        }
        try {
            cache.put(appCorrelationId, Long.toString(localOtid));
        } catch (AvailabilityException e) {
            LOG.warn("app-correlation register({}) skipped: cache unavailable (partition minority?)",
                    appCorrelationId);
        }
    }

    /** @return the OTID string, or {@code null} when unknown/unavailable. */
    public String resolve(String appCorrelationId) {
        if (appCorrelationId == null) {
            return null;
        }
        try {
            return cache.get(appCorrelationId);
        } catch (AvailabilityException e) {
            return null;
        }
    }

    /** Drop the mapping when the dialog closes (only if it still points here). */
    public void remove(String appCorrelationId, long localOtid) {
        if (appCorrelationId == null) {
            return;
        }
        try {
            cache.remove(appCorrelationId, Long.toString(localOtid));
        } catch (AvailabilityException e) {
            LOG.debug("app-correlation remove({}) deferred: cache unavailable", appCorrelationId);
        }
    }
}
