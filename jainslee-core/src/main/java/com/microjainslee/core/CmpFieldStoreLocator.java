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

/**
 * Process-local registry of the active {@link CmpFieldStore}.
 * <p>
 * Set by {@link MicroSleeContainer} on construction and on
 * {@link MicroSleeContainer#stop()}. User code (e.g.
 * {@link CmpBackedSbb}) calls {@link #get()} to look up the store
 * without having to thread it through every SBB constructor.
 *
 * <p>Kept deliberately minimal: a single static {@code ThreadLocal} so
 * embedders running multiple {@code MicroSleeContainer} instances in
 * the same JVM (e.g. a test harness) can isolate their CMP state.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class CmpFieldStoreLocator {

    private static final ThreadLocal<CmpFieldStore> CURRENT = new ThreadLocal<CmpFieldStore>();

    private CmpFieldStoreLocator() {
        // utility
    }

    /** Bind the store for the current thread / container scope. */
    public static void set(CmpFieldStore store) {
        if (store == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(store);
        }
    }

    /** Lookup the currently bound store, or {@code null} if none. */
    public static CmpFieldStore get() {
        return CURRENT.get();
    }
}