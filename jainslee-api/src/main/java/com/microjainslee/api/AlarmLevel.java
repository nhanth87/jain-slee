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
 * JAIN-SLEE 1.1 §15 — alarm severity levels (ITU X.733 subset).
 */
public enum AlarmLevel {
    WARNING,
    MINOR,
    MAJOR,
    CRITICAL,
    CLEARED
}
