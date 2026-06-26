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

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.ServiceID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ServiceIDTest {

    @Test
    public void equalsAndHashCodeUseNameVendorVersion() {
        ServiceID left = new ServiceID("ussd", "com.example", "1.0");
        ServiceID right = new ServiceID("ussd", "com.example", "1.0");
        ServiceID different = new ServiceID("ussd", "com.example", "2.0");

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, different);
        assertEquals("com.example/ussd/1.0", left.toString());
    }
}
