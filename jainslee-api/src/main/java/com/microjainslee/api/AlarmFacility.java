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
 * JAIN-SLEE 1.1 §11 — Alarm Facility.
 * <p>
 * Container-level facility for raising and clearing operator alarms.
 * Distinct from the lower-level {@link AlarmPort} callback sink: this
 * is the API exposed through {@link SbbContext#getAlarmFacility()} and
 * lets SBBs report their own health to the operator without depending on
 * the container's {@link AlarmPort} implementation.
 * <p>
 * The default {@code getAlarmFacility()} implementation on
 * {@link SbbContext} returns {@code null} so that legacy SBB code keeps
 * compiling; embedders that want alarms must override it.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface AlarmFacility {

    /**
     * Raise or update an alarm.
     *
     * @param alarmType  alarm category (e.g. {@code "link-down"}, {@code "queue-full"})
     * @param instanceId identifier of the affected instance (SBB entity name,
     *                   RA entity name, etc.)
     * @param level      severity level (must not be {@code null})
     * @param message    human-readable description (may be {@code null})
     */
    void raise(String alarmType, String instanceId, AlarmLevel level, String message);

    /**
     * Clear a previously raised alarm.
     *
     * @param alarmType  alarm category
     * @param instanceId identifier of the affected instance
     */
    void clear(String alarmType, String instanceId);
}
