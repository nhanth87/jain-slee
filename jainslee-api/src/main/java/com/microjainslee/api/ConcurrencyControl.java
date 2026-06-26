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
 * JAIN-SLEE 1.1 §6.3.4 — SBB concurrency control mode on an Activity Context.
 */
public enum ConcurrencyControl {
    /** Serialize all events on the AC (default). */
    TRANSACTION,
    /** Events of different types may be delivered concurrently. */
    EVENT_TYPE_INDEPENDENT,
    /** Serialize delivery per event type on the AC. */
    EVENT_TYPE
}
