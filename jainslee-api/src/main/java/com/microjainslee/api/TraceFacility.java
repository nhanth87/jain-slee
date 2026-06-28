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
 * JAIN-SLEE 1.1 §16 — Trace Facility surface available to Resource Adaptors.
 * <p>
 * Lets an RA emit operator-visible trace records with the standard
 * {@link TraceLevel} vocabulary. Embedders wire their preferred logging
 * backend (Logback, log4j2, JUL) into the facility; the default
 * {@code LogbackTraceFacility} routes to log4j2 so micro-jainslee keeps
 * a single sink across modules.
 */
public interface TraceFacility {

    void trace(String message);

    void trace(TraceLevel level, String message);

    boolean isTraceEnabled(TraceLevel level);
}
