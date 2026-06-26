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
 * Protocol timer scheduler backed by a hashed-wheel timer, separate from the event dispatcher.
 */
public interface TimerScheduler {

    TimerHandle schedule(TimerRecord record, long delayMillis, TimerCallback callback);

    void cancel(long timerId);

    void cancelAll(long dialogId);

    void start();

    void stop();
}
