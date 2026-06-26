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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;

/**
 * JAIN-SLEE 1.1 §5.14 — policy invoked when an SBB throws during event delivery.
 */
public interface ErrorHandlingPolicy {
    void onSbbException(SbbLocalObject sbb, Exception e, SleeEvent event, ActivityContextInterface aci);
}
