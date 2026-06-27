/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

/**
 * Optional contract for SBB classes that participate in the {@code SbbTypePool}.
 * Invoked before an instance is returned to the idle queue so per-session state
 * can be cleared without calling {@code sbbRemove}.
 */
public interface PoolableSbb extends Sbb {

    /**
     * Reset transient session state before the instance is pooled for reuse.
     *
     * @param entityId external entity id about to be released
     */
    void resetForReuse(String entityId);
}
