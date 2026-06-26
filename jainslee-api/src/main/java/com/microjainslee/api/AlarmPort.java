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
 * JAIN-SLEE 1.1 §15 — Alarm Port interface.
 * Provides operator alerting facilities.
 */
public interface AlarmPort {

    /**
     * Raise or update an alarm.
     *
     * @param alarmId   unique alarm identifier within the source
     * @param level     severity level
     * @param message   human-readable description
     */
    void raiseAlarm(String alarmId, AlarmLevel level, String message);

    /**
     * Clear a previously raised alarm.
     *
     * @param alarmId unique alarm identifier within the source
     */
    void clearAlarm(String alarmId);
}
