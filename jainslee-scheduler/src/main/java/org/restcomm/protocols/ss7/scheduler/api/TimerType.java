/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Vendored from jSS7 scheduler (RestComm/jss7 9.5.0).
 * Original package: org.restcomm.protocols.ss7.scheduler
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */


package org.restcomm.protocols.ss7.scheduler.api;

/**
 * Protocol timer categories managed by {@link TimerScheduler}, separate from the event dispatcher.
 */
public enum TimerType {

    /** micro-jainslee SLEE timer facility (not present in upstream jSS7 9.5). */
    SLEE_TIMER,
    TCAP_INVOKE_TIMEOUT,
    TCAP_DIALOG_TIMEOUT,
    MAP_T_GUARD_SHORT,
    MAP_T_GUARD_MEDIUM,
    MAP_T_GUARD_LONG,
    CAP_T_SHORT,
    CAP_T_MEDIUM,
    CAP_T_LONG,
    M3UA_FSM_TICK,
    SCCP_CONN_EST,
    SCCP_IAS,
    SCCP_IAR,
    SCCP_REL,
    SCCP_REPEAT_REL,
    SCCP_INT,
    SCCP_GUARD,
    SCCP_RESET,
    SCCP_REASSEMBLY
}
