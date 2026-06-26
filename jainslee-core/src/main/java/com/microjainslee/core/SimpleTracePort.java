package com.microjainslee.core;

import com.microjainslee.api.TracePort;

import java.util.logging.Logger;

/**
 * java.util.logging backed tracer for embedded mode.
 */
public final class SimpleTracePort implements TracePort {

    private final Logger logger;

    public SimpleTracePort(String tracerName) {
        this.logger = Logger.getLogger(tracerName == null ? "micro-jainslee" : tracerName);
    }

    @Override
    public void trace(String message) {
        logger.info(message);
    }
}
