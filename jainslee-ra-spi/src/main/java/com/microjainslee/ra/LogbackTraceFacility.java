/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra;

import com.microjainslee.api.TraceFacility;
import com.microjainslee.api.TraceLevel;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Perfect Core S5 — log4j2-backed {@link TraceFacility}.
 * <p>
 * Despite the legacy name, micro-jainslee routes traces through
 * log4j2 (which is on every kernel module's classpath) rather than
 * Logback, so embedders get a single sink regardless of which
 * facade they prefer.
 */
public final class LogbackTraceFacility implements TraceFacility {

    public static final LogbackTraceFacility INSTANCE = new LogbackTraceFacility();

    private final Logger logger = LogManager.getLogger("micro-jainslee.ra.trace");

    private LogbackTraceFacility() {}

    @Override
    public void trace(String message) {
        trace(TraceLevel.INFO, message);
    }

    @Override
    public void trace(TraceLevel level, String message) {
        if (level == null) {
            level = TraceLevel.INFO;
        }
        logger.log(toLog4j(level), message);
    }

    @Override
    public boolean isTraceEnabled(TraceLevel level) {
        if (level == null) return true;
        return logger.isEnabled(toLog4j(level));
    }

    private static Level toLog4j(TraceLevel level) {
        return switch (level) {
            case FINE     -> Level.DEBUG;
            case FINER    -> Level.TRACE;
            case FINEST   -> Level.TRACE;
            case WARNING  -> Level.WARN;
            case SEVERE   -> Level.ERROR;
            default       -> Level.INFO;
        };
    }
}
