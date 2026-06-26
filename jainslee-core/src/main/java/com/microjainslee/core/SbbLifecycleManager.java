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

import com.microjainslee.api.*;

/**
 * JAIN-SLEE 1.1 §8.4 — SBB Lifecycle Manager.
 * Manages SBB lifecycle transitions.
 */
public class SbbLifecycleManager {
    public enum State { DOES_NOT_EXIST, POOLED, READY, ACTIVE, PASSIVE }

    public void create(Sbb sbb, SbbContext context) {
        sbb.setSbbContext(context);
        sbb.sbbCreate();
    }

    public void activate(Sbb sbb) {
        sbb.sbbActivate();
    }

    public void passivate(Sbb sbb) {
        sbb.sbbPassivate();
    }

    public void remove(Sbb sbb) {
        sbb.sbbRemove();
    }
}