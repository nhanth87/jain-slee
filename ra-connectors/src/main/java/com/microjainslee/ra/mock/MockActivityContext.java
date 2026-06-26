/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.mock;

import com.microjainslee.api.*;

/**
 * Mock Activity Context for testing.
 */
public class MockActivityContext implements ActivityContextInterface {
    @Override
    public String getActivityContextName() {
        return "MockActivityContext";
    }

    @Override
    public void attach(SbbLocalObject sbbLocalObject) {}

    @Override
    public void detach(SbbLocalObject sbbLocalObject) {}
}