/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.transport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mobicents.protocols.sctp.spi.SctpCongestionReporter;
import org.mobicents.protocols.sctp.spi.SctpCongestionSnapshot;

/** Level-transition only — never per packet. */
final class Log4jCongestionReporter implements SctpCongestionReporter {
    private static final Logger LOG = LogManager.getLogger(Log4jCongestionReporter.class);

    @Override
    public void onCongestion(SctpCongestionSnapshot snapshot) {
        LOG.warn("ss7 congestion level={} source={} permittedTps={} retryAfter={}s — throttling MAP DATA",
                snapshot.level(), snapshot.source(), snapshot.permittedTps(), snapshot.retryAfterSeconds());
    }

    @Override
    public void onCongestionAbated(SctpCongestionSnapshot snapshot) {
        LOG.info("ss7 congestion abated level={} source={} permittedTps={}",
                snapshot.level(), snapshot.source(), snapshot.permittedTps());
    }
}
