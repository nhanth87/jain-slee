/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.TracePort;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * log4j2-backed tracer for embedded mode.
 */
public final class SimpleTracePort implements TracePort {

    private final Logger logger;

    public SimpleTracePort(String tracerName) {
        this.logger = LogManager.getLogger(tracerName == null ? "micro-jainslee" : tracerName);
    }

    @Override
    public void trace(String message) {
        logger.info(message);
    }
}
