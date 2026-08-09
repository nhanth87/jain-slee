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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Scrapeable counters for RA sticky HA (ADR 0002). */
public final class RaHaMetrics {

    private final LongAdder stickyReject = new LongAdder();
    private final LongAdder stickyForward = new LongAdder();
    private final LongAdder ownerClaim = new LongAdder();
    private final LongAdder metaPut = new LongAdder();
    private final LongAdder raCheckpointOk = new LongAdder();
    private final LongAdder raCheckpointSkip = new LongAdder();

    public void stickyReject() {
        stickyReject.increment();
    }

    public void stickyForward() {
        stickyForward.increment();
    }

    public void ownerClaim() {
        ownerClaim.increment();
    }

    public void metaPut() {
        metaPut.increment();
    }

    public void raCheckpointOk() {
        raCheckpointOk.increment();
    }

    public void raCheckpointSkip() {
        raCheckpointSkip.increment();
    }

    public long stickyRejectCount() {
        return stickyReject.sum();
    }

    public long stickyForwardCount() {
        return stickyForward.sum();
    }

    public long ownerClaimCount() {
        return ownerClaim.sum();
    }

    public long metaPutCount() {
        return metaPut.sum();
    }

    public long raCheckpointOkCount() {
        return raCheckpointOk.sum();
    }

    public long raCheckpointSkipCount() {
        return raCheckpointSkip.sum();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("sticky_reject", stickyRejectCount());
        m.put("sticky_forward", stickyForwardCount());
        m.put("owner_claim", ownerClaimCount());
        m.put("meta_put", metaPutCount());
        m.put("ra_checkpoint_ok", raCheckpointOkCount());
        m.put("ra_checkpoint_skip", raCheckpointSkipCount());
        return m;
    }
}
