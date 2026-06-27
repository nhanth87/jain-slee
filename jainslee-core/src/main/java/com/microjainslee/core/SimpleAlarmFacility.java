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

import com.microjainslee.api.AlarmFacility;
import com.microjainslee.api.AlarmLevel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link AlarmFacility} for the embedded container.
 * <p>
 * Tracks the most recent active level per {@code (type,instanceId)} key
 * and re-uses the underlying {@link SimpleAlarmPort} log4j2 sink so the
 * operator-visible output is the same regardless of which entry point
 * raised the alarm.
 * <p>
 * {@link #snapshot()} is exposed for diagnostics and tests; it returns
 * an unmodifiable view so callers cannot mutate the live state.
 */
public final class SimpleAlarmFacility implements AlarmFacility {

    private static final Logger LOG = LogManager.getLogger(SimpleAlarmFacility.class);

    private final ConcurrentHashMap<String, AlarmLevel> active = new ConcurrentHashMap<>();
    private final SimpleAlarmPort alarmPort;

    public SimpleAlarmFacility() {
        this(new SimpleAlarmPort());
    }

    public SimpleAlarmFacility(SimpleAlarmPort alarmPort) {
        this.alarmPort = alarmPort;
    }

    @Override
    public void raise(String alarmType, String instanceId, AlarmLevel level, String message) {
        if (alarmType == null || level == null || level == AlarmLevel.CLEARED) {
            return;
        }
        String id = key(alarmType, instanceId);
        active.put(id, level);
        // Forward to the underlying port so log output is unified.
        if (alarmPort != null) {
            alarmPort.raiseAlarm(id, level, message);
        }
        LOG.info("ALARM raised type={} instance={} level={}", alarmType, instanceId, level);
    }

    @Override
    public void clear(String alarmType, String instanceId) {
        if (alarmType == null) {
            return;
        }
        String id = key(alarmType, instanceId);
        AlarmLevel prev = active.remove(id);
        if (alarmPort != null) {
            alarmPort.clearAlarm(id);
        }
        LOG.info("ALARM cleared type={} instance={} previousLevel={}", alarmType, instanceId, prev);
    }

    /**
     * @return immutable snapshot of all currently active {@code (key → level)} pairs
     */
    public Map<String, AlarmLevel> snapshot() {
        return Collections.unmodifiableMap(active);
    }

    private static String key(String alarmType, String instanceId) {
        return alarmType + "::" + (instanceId == null ? "" : instanceId);
    }
}
