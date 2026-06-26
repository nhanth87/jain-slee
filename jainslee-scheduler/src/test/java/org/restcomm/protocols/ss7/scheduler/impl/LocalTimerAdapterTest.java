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

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.restcomm.protocols.ss7.scheduler.api.TimerCallback;
import org.restcomm.protocols.ss7.scheduler.api.TimerHandle;
import org.restcomm.protocols.ss7.scheduler.api.TimerRecord;
import org.restcomm.protocols.ss7.scheduler.api.TimerType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class LocalTimerAdapterTest {

    private LocalTimerAdapter adapter;

    @BeforeMethod
    public void setUp() {
        adapter = new LocalTimerAdapter(new HashedWheelTimerFacade(10L, TimeUnit.MILLISECONDS));
        adapter.start();
    }

    @AfterMethod
    public void tearDown() {
        adapter.stop();
    }

    @Test
    public void scheduleFiresCallback() throws Exception {
        final CountDownLatch fired = new CountDownLatch(1);
        final AtomicBoolean sawRecord = new AtomicBoolean(false);
        TimerRecord record = new TimerRecord(1L, 10L, TimerType.TCAP_INVOKE_TIMEOUT, 0L, "node-a", 1, 0L);

        TimerHandle handle = adapter.schedule(record, 50L, new TimerCallback() {
            @Override
            public void onTimerFire(TimerRecord firedRecord) {
                sawRecord.set(firedRecord.getTimerId() == 1L);
                fired.countDown();
            }
        });

        assertTrue(fired.await(2, TimeUnit.SECONDS));
        assertTrue(sawRecord.get());
        assertTrue(handle.hasFired());
        assertFalse(handle.isCancelled());
    }

    @Test
    public void cancelPreventsFire() throws Exception {
        final CountDownLatch fired = new CountDownLatch(1);
        TimerRecord record = new TimerRecord(2L, 20L, TimerType.TCAP_DIALOG_TIMEOUT, 0L, "node-a", 1, 0L);

        TimerHandle handle = adapter.schedule(record, 200L, new TimerCallback() {
            @Override
            public void onTimerFire(TimerRecord firedRecord) {
                fired.countDown();
            }
        });

        handle.cancel();

        assertFalse(fired.await(500, TimeUnit.MILLISECONDS));
        assertTrue(handle.isCancelled());
        assertFalse(handle.hasFired());
    }

    @Test
    public void cancelAllCancelsDialogTimers() throws Exception {
        final CountDownLatch fired = new CountDownLatch(1);
        TimerRecord first = new TimerRecord(3L, 30L, TimerType.MAP_T_GUARD_SHORT, 0L, "node-a", 1, 0L);
        TimerRecord second = new TimerRecord(4L, 30L, TimerType.MAP_T_GUARD_LONG, 0L, "node-a", 1, 0L);

        TimerHandle firstHandle = adapter.schedule(first, 200L, new TimerCallback() {
            @Override
            public void onTimerFire(TimerRecord firedRecord) {
                fired.countDown();
            }
        });
        TimerHandle secondHandle = adapter.schedule(second, 200L, new TimerCallback() {
            @Override
            public void onTimerFire(TimerRecord firedRecord) {
                fired.countDown();
            }
        });

        adapter.cancelAll(30L);

        assertFalse(fired.await(500, TimeUnit.MILLISECONDS));
        assertTrue(firstHandle.isCancelled());
        assertTrue(secondHandle.isCancelled());
    }
}
