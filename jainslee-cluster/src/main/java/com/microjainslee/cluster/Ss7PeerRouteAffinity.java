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

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Infinispan-coordinated <b>N–N</b> peer-route load-balance for <em>new</em> outbound
 * sessions (NI push, GTT catch-all, MAP2MAP hop without MO ingress ASP).
 *
 * <p><b>Topology:</b> 1 AS with N ASPs / peer PCs (example Digicom: L1-1404 + L2-1403,
 * but N is not limited to 2). Not active-standby, not OVERRIDE single-primary, not
 * A-A pair-only — any of N candidates may be selected for a new dialog.
 *
 * <p><b>After pick:</b> caller pins {@link PeerRoute#aspName()} /
 * {@link PeerRoute#peerPc()} onto the new TCAP dialog ({@code setPreferredAspName} /
 * {@code setRemotePc}) so subsequent messages stay sticky (jSS7 {@code AsImpl.write}).
 *
 * <p>Same {@link ClusterManager} fabric as ADR 0001 — never a second cluster / Redis.
 */
public final class Ss7PeerRouteAffinity {

    /** Counter key prefix for round-robin across N candidates (cluster-wide). */
    public static final String COUNTER_KEY_PREFIX = "rr:";

    /** Affinity pin key prefix: {@code pin:<affinityKey>} → {@link PeerRoute}. */
    public static final String PIN_KEY_PREFIX = "pin:";

    private final Cache<String, Object> cache;
    private final AtomicLong localFallback = new AtomicLong();

    public Ss7PeerRouteAffinity(ClusterManager clusterManager) {
        Objects.requireNonNull(clusterManager, "clusterManager");
        CacheMode mode = clusterManager.isClusterMode() ? CacheMode.REPL_SYNC : CacheMode.LOCAL;
        this.cache = clusterManager.getCache(Ss7DialogCacheNames.SS7_PEER_ROUTE_LB, mode);
    }

    /**
     * Pick one of N candidate routes for a new session.
     *
     * <ol>
     *   <li>If {@code affinityKey} already pinned → return that pin (sticky across cluster).</li>
     *   <li>Else round-robin via ISPN counter keyed by {@code poolKey}, then pin.</li>
     * </ol>
     *
     * @param poolKey     logical pool (e.g. {@code "ni:networkId=0"} or {@code "gtt:0"})
     * @param affinityKey session key (corrId / MSISDN hash) — may be null for pure RR
     * @param candidates  N peer routes (aspName + peerPc); must be non-empty
     */
    public PeerRoute pickAndPin(String poolKey, String affinityKey, List<PeerRoute> candidates) {
        Objects.requireNonNull(poolKey, "poolKey");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must be non-empty (N≥1 for N–N)");
        }
        for (PeerRoute c : candidates) {
            Objects.requireNonNull(c, "candidate");
        }

        if (affinityKey != null && !affinityKey.isEmpty()) {
            String pinKey = PIN_KEY_PREFIX + poolKey + ':' + affinityKey;
            Object existing = cache.get(pinKey);
            if (existing instanceof PeerRoute pinned) {
                return pinned;
            }
            PeerRoute chosen = selectRoundRobin(poolKey, candidates);
            Object raced = cache.putIfAbsent(pinKey, chosen);
            if (raced instanceof PeerRoute won) {
                return won;
            }
            return chosen;
        }
        return selectRoundRobin(poolKey, candidates);
    }

    /**
     * Clear a pin (dialog end / abort). No-op when affinityKey blank.
     */
    public void clearPin(String poolKey, String affinityKey) {
        if (affinityKey == null || affinityKey.isEmpty() || poolKey == null) {
            return;
        }
        cache.remove(PIN_KEY_PREFIX + poolKey + ':' + affinityKey);
    }

    private PeerRoute selectRoundRobin(String poolKey, List<PeerRoute> candidates) {
        String counterKey = COUNTER_KEY_PREFIX + poolKey;
        long seq = nextCounter(counterKey);
        int idx = Math.floorMod(seq, candidates.size());
        return candidates.get(idx);
    }

    private long nextCounter(String counterKey) {
        for (int attempt = 0; attempt < 64; attempt++) {
            Object cur = cache.get(counterKey);
            long next;
            if (cur instanceof Long l) {
                next = l + 1L;
            } else if (cur instanceof Number n) {
                next = n.longValue() + 1L;
            } else {
                next = 1L;
            }
            if (cur == null) {
                Object raced = cache.putIfAbsent(counterKey, next);
                if (raced == null) {
                    return next;
                }
                continue;
            }
            if (cache.replace(counterKey, cur, next)) {
                return next;
            }
        }
        // Extreme contention / local-only fallback — still cycles across N.
        return localFallback.incrementAndGet();
    }

    /**
     * One of N peer routes: M3UA ASP name + remote PC for SCCP/MAP sticky DPC.
     */
    public record PeerRoute(String aspName, int peerPc) implements Serializable {
        private static final long serialVersionUID = 1L;

        public PeerRoute {
            Objects.requireNonNull(aspName, "aspName");
            if (aspName.isEmpty()) {
                throw new IllegalArgumentException("aspName must be non-blank");
            }
        }
    }
}
