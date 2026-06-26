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

import com.microjainslee.api.AlarmLevel;
import com.microjainslee.api.AlarmPort;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory alarm stub for embedded mode — logs alarms and tracks active state.
 */
public final class SimpleAlarmPort implements AlarmPort {

    private static final Logger LOG = LogManager.getLogger(SimpleAlarmPort.class);

    private final ConcurrentHashMap<String, AlarmLevel> activeAlarms =
            new ConcurrentHashMap<String, AlarmLevel>();

    @Override
    public void raiseAlarm(String alarmId, AlarmLevel level, String message) {
        if (alarmId == null || level == null) {
            return;
        }
        activeAlarms.put(alarmId, level);
        LOG.warn("ALARM [{}] {} — {}", alarmId, level, message == null ? "" : message);
    }

    @Override
    public void clearAlarm(String alarmId) {
        if (alarmId == null) {
            return;
        }
        activeAlarms.remove(alarmId);
        LOG.info("ALARM CLEARED [{}]", alarmId);
    }

    public AlarmLevel getActiveLevel(String alarmId) {
        return activeAlarms.get(alarmId);
    }
}
