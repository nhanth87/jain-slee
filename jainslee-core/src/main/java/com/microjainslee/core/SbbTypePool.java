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

import com.microjainslee.api.PoolableSbb;
import com.microjainslee.api.Sbb;

/**
 * Per-SBB-class object pool backed by {@link SbbObjectPool}.
 */
public final class SbbTypePool {

    private final SbbTypePoolConfig config;
    private final SbbObjectPool delegate;
    private final SbbLifecycleManager lifecycleManager;

    public SbbTypePool(SbbTypePoolConfig config, SbbLifecycleManager lifecycleManager) {
        this.config = config;
        this.lifecycleManager = lifecycleManager;
        this.delegate = new SbbObjectPool(config.getMinIdle(), config.getMaxActive(),
                config.getFactory());
    }

    public Sbb borrow() {
        return delegate.acquire();
    }

    public void release(Sbb sbb) {
        if (sbb == null) {
            return;
        }
        if (sbb instanceof PoolableSbb) {
            try {
                ((PoolableSbb) sbb).resetForReuse(null);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        if (config.getResetHook() != null) {
            try {
                config.getResetHook().accept(sbb);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        lifecycleManager.setState(sbb, SbbLifecycleManager.State.POOLED);
        delegate.release(sbb);
    }

    public boolean isPooledReuse(Sbb sbb) {
        return lifecycleManager.getState(sbb) == SbbLifecycleManager.State.POOLED;
    }

    public EventMask getDefaultEventMask() {
        return config.getDefaultEventMask();
    }

    public int idleCount() {
        return delegate.idleCount();
    }

    public int createdCount() {
        return delegate.createdCount();
    }
}
