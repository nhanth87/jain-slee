/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.codegen.example;

import com.microjainslee.api.Sbb;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.OffHeap;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.api.annotations.StorageType;

/**
 * Production-2 example: opt-in off-heap CMP session SBB.
 *
 * <p>APT emits {@code OffHeapDemoSessionSbb$OffHeapConcrete}; deploy-time
 * {@code ConcreteSbbGenerator} emits {@code $Concrete}. Both bind slots via
 * {@link com.microjainslee.api.OffHeapBindable}.
 */
@SbbAnnotation(name = "OffHeapDemoSessionSbb", vendor = "com.microjainslee", version = "1.2.0")
@OffHeap(storage = StorageType.DIRECT, maxSlots = 131_072)
public abstract class OffHeapDemoSessionSbb implements Sbb {

    @CmpField("msisdn")
    public abstract String getMsisdn();

    @CmpField("msisdn")
    public abstract void setMsisdn(String v);

    @CmpField("menuState")
    public abstract int getMenuState();

    @CmpField("menuState")
    public abstract void setMenuState(int v);

    @CmpField("startedAt")
    public abstract long getStartedAt();

    @CmpField("startedAt")
    public abstract void setStartedAt(long v);

    @Override
    public void sbbCreate() {
    }

    @Override
    public void sbbActivate() {
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }
}
