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
 * JAIN-SLEE 1.1 §8.4 — SBB Lifecycle interface.
 * All SBBs must implement this interface.
 */
public interface Sbb {
    default void sbbCreate() {}
    default void sbbActivate() {}
    default void sbbPassivate() {}
    default void sbbRemove() {}
    default void sbbLoad() {}
    default void sbbStore() {}
    default void sbbExceptionThrown(Exception e, Object event, ActivityContextInterface aci) {}
    default void setSbbContext(SbbContext context) {}
}