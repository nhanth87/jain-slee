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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.testng.annotations.Test;

public class TimerRecordSerializationTest {

    @Test
    public void roundTripSerialization() throws Exception {
        TimerRecord original = new TimerRecord(42L, 1001L, TimerType.MAP_T_GUARD_MEDIUM, 5000L, "node-1", 3, 1000L);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(original);
        oos.close();

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()));
        TimerRecord restored = (TimerRecord) ois.readObject();
        ois.close();

        assertNotNull(restored);
        assertEquals(restored.getTimerId(), original.getTimerId());
        assertEquals(restored.getDialogId(), original.getDialogId());
        assertEquals(restored.getTimerType(), original.getTimerType());
        assertEquals(restored.getExpiresAtMillis(), original.getExpiresAtMillis());
        assertEquals(restored.getNodeId(), original.getNodeId());
        assertEquals(restored.getVersion(), original.getVersion());
        assertEquals(restored.getCreatedAtMillis(), original.getCreatedAtMillis());
    }
}
