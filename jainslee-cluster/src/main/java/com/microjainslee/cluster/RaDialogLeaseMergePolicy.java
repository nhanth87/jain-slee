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

import java.util.List;

import org.infinispan.container.entries.CacheEntry;
import org.infinispan.conflict.EntryMergePolicy;

/**
 * ADR 0007 D12 — when a partition heals, the lease with the highest
 * {@code generation} wins.
 *
 * <p>
 * Only the majority side can write during a split (the lease cache denies
 * reads and writes on the minority), so a takeover there bumps the generation
 * and the minority's copy is always older. Choosing by generation, not by
 * "preferred partition", keeps that true even when the preferred side is the
 * one that lost ownership. A removed entry (dialog ended) loses to a live one
 * only if the live one is newer.
 */
public final class RaDialogLeaseMergePolicy implements EntryMergePolicy<String, RaDialogLease> {

    @Override
    public CacheEntry<String, RaDialogLease> merge(CacheEntry<String, RaDialogLease> preferredEntry,
            List<CacheEntry<String, RaDialogLease>> otherEntries) {
        CacheEntry<String, RaDialogLease> best = preferredEntry;
        for (CacheEntry<String, RaDialogLease> candidate : otherEntries) {
            if (generation(candidate) > generation(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static long generation(CacheEntry<String, RaDialogLease> entry) {
        if (entry == null || entry.getValue() == null) {
            return Long.MIN_VALUE;
        }
        return entry.getValue().generation();
    }
}
