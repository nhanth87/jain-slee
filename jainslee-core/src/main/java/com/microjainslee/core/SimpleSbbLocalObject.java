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

/**
 * Simple immutable SBB local object for embedded deployments.
 */
public final class SimpleSbbLocalObject implements SbbLocalObject {

    /**
     * Callback invoked after {@link #remove()} completes on the owning entity thread.
     */
    public interface RemovalListener {
        void onRemoved(SimpleSbbLocalObject localObject);
    }

    private final SbbID sbbID;
    private final Sbb sbb;
    private final VirtualThreadSbbEntityPool entityPool;
    private final RemovalListener removalListener;
    private volatile int priority;
    private volatile boolean removed;

    public SimpleSbbLocalObject(SbbID sbbID, Sbb sbb) {
        this(sbbID, sbb, null, null, 0);
    }

    public SimpleSbbLocalObject(SbbID sbbID, Sbb sbb, VirtualThreadSbbEntityPool entityPool,
            RemovalListener removalListener, int priority) {
        if (sbbID == null || sbb == null) {
            throw new IllegalArgumentException("sbbID and sbb are required");
        }
        this.sbbID = sbbID;
        this.sbb = sbb;
        this.entityPool = entityPool;
        this.removalListener = removalListener;
        this.priority = priority;
    }

    @Override
    public Sbb getSbb() {
        return sbb;
    }

    @Override
    public SbbID getSbbID() {
        return sbbID;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public boolean isRemoved() {
        return removed;
    }

    @Override
    public void remove() {
        invokeLocally(new Runnable() {
            @Override
            public void run() {
                if (removed) {
                    return;
                }
                removed = true;
                sbb.sbbRemove();
                if (removalListener != null) {
                    removalListener.onRemoved(SimpleSbbLocalObject.this);
                }
            }
        });
    }

    @Override
    public void invokeLocally(Runnable action) {
        SbbLocalInvoker.invoke(entityPool, this, action);
    }
}
