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
 * JAIN-SLEE 1.1 §16 — Trace Port interface.
 * Provides SBB runtime tracing facilities.
 */
public interface TracePort {

    /**
     * Emit a trace at {@link TraceLevel#INFO}.
     */
    void trace(String message);

    /**
     * Emit a trace at the given level.
     */
    void trace(TraceLevel level, String message);
}