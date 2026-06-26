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


package org.restcomm.protocols.ss7.scheduler.impl;

import org.restcomm.protocols.ss7.scheduler.api.TimerCallback;
import org.restcomm.protocols.ss7.scheduler.api.TimerHandle;
import org.restcomm.protocols.ss7.scheduler.api.TimerRecord;
import org.restcomm.protocols.ss7.scheduler.api.TimerScheduler;

/**
 * No-op {@link TimerScheduler} for unit tests.
 */
public class NoOpTimerAdapter implements TimerScheduler {

    @Override
    public TimerHandle schedule(TimerRecord record, long delayMillis, TimerCallback callback) {
        return new TimerHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean hasFired() {
                return false;
            }
        };
    }

    @Override
    public void cancel(long timerId) {
    }

    @Override
    public void cancelAll(long dialogId) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
