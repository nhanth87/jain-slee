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

import com.microjainslee.api.AlarmFacility;
import com.microjainslee.api.AlarmLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Perfect Core S5 — no-op {@link AlarmFacility} used when an RA was
 * wired without an alarm sink (e.g. unit tests, lightweight embeddings).
 * Calls are logged at DEBUG so test assertions can still confirm they
 * were issued without raising a real operator alarm.
 */
public final class NoopAlarmFacility implements AlarmFacility {

    private static final Logger LOG = LogManager.getLogger(NoopAlarmFacility.class);

    public static final NoopAlarmFacility INSTANCE = new NoopAlarmFacility();

    private NoopAlarmFacility() {}

    @Override
    public void raise(String alarmType, String instanceId, AlarmLevel level, String message) {
        LOG.debug("[noop-alarm] raise type={} instance={} level={} msg={}",
                alarmType, instanceId, level, message);
    }

    @Override
    public void clear(String alarmType, String instanceId) {
        LOG.debug("[noop-alarm] clear type={} instance={}", alarmType, instanceId);
    }
}
