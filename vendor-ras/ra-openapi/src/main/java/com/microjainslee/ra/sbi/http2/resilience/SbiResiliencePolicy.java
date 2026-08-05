/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http2.resilience;

import com.microjainslee.ra.sbi.openapi.headers.SbiHeaderCodec;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TS 29.500 §5.2.8 oriented retry + per-peer circuit/bulkhead.
 */
public final class SbiResiliencePolicy {

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private int maxRetries = 2;
    private long backoffMs = 200;
    private long circuitOpenMs = 5_000;
    private int failureThreshold = 5;
    private int bulkheadMax = 64;

    private final ConcurrentHashMap<String, PeerState> peers = new ConcurrentHashMap<>();

    public void setMaxRetries(int n) { this.maxRetries = Math.max(0, n); }
    public void setBackoffMs(long ms) { this.backoffMs = Math.max(0, ms); }
    public void setCircuitOpenMs(long ms) { this.circuitOpenMs = Math.max(100, ms); }
    public void setFailureThreshold(int n) { this.failureThreshold = Math.max(1, n); }
    public void setBulkheadMax(int n) { this.bulkheadMax = Math.max(1, n); }

    public int maxRetries() { return maxRetries; }
    public long backoffMs() { return backoffMs; }

    public int effectiveMaxRetries(SbiHeaderCodec headers, Integer override) {
        if (headers != null && headers.noRetries()) {
            return 0;
        }
        if (override != null) {
            return Math.max(0, override);
        }
        return maxRetries;
    }

    public boolean allowRequest(String peerKey) {
        PeerState st = peers.computeIfAbsent(peerKey, k -> new PeerState());
        if (st.inFlight.get() >= bulkheadMax) {
            return false;
        }
        CircuitState cs = st.circuit();
        if (cs == CircuitState.OPEN) {
            return false;
        }
        return true;
    }

    public void acquire(String peerKey) {
        peers.computeIfAbsent(peerKey, k -> new PeerState()).inFlight.incrementAndGet();
    }

    public void release(String peerKey, boolean success) {
        PeerState st = peers.computeIfAbsent(peerKey, k -> new PeerState());
        st.inFlight.updateAndGet(v -> Math.max(0, v - 1));
        if (success) {
            st.consecutiveFailures.set(0);
            st.openedAtMs.set(0);
        } else {
            int f = st.consecutiveFailures.incrementAndGet();
            if (f >= failureThreshold) {
                st.openedAtMs.set(System.currentTimeMillis());
            }
        }
    }

    public Map<String, Object> snapshot(String peerKey) {
        PeerState st = peers.get(peerKey);
        if (st == null) {
            return Map.of("peer", peerKey, "circuit", CircuitState.CLOSED.name(), "inFlight", 0);
        }
        return Map.of(
                "peer", peerKey,
                "circuit", st.circuit().name(),
                "inFlight", st.inFlight.get(),
                "consecutiveFailures", st.consecutiveFailures.get());
    }

    public Map<String, Object> allPeersSnapshot() {
        Map<String, Object> out = new ConcurrentHashMap<>();
        peers.forEach((k, v) -> out.put(k, snapshot(k)));
        return out;
    }

    private final class PeerState {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicLong openedAtMs = new AtomicLong();

        CircuitState circuit() {
            long opened = openedAtMs.get();
            if (opened <= 0) {
                return CircuitState.CLOSED;
            }
            if (System.currentTimeMillis() - opened >= circuitOpenMs) {
                return CircuitState.HALF_OPEN;
            }
            return CircuitState.OPEN;
        }
    }

    public static boolean shouldRetryStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    public long retryDelayMs(int attempt, String retryAfterHeader) {
        if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
            try {
                return Long.parseLong(retryAfterHeader.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // fall through — may be HTTP-date; ignore for lab
            }
        }
        return backoffMs * (1L << Math.min(attempt, 6));
    }

    public Instant now() {
        return Instant.now();
    }
}
