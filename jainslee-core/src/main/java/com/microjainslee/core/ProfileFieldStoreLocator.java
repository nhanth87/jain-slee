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
 * Process-local registry of the active {@link InMemoryProfileFacility}.
 * <p>
 * Set by {@link MicroSleeContainer} on {@link MicroSleeContainer#start()} and
 * cleared on {@link MicroSleeContainer#stop()}. The
 * {@link ProfileAccessorInvoker} shadow implementation in this module calls
 * {@link #get()} to resolve the current facility without threading it
 * through every {@code Profile} subclass — the same pattern
 * {@link CmpFieldStoreLocator} uses for SBBs.
 *
 * <p>Kept deliberately minimal: a single static {@code ThreadLocal} so
 * embedders running multiple {@code MicroSleeContainer} instances in the
 * same JVM (e.g. a test harness) can isolate their profile state.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileFieldStoreLocator {

    private static final ThreadLocal<InMemoryProfileFacility> CURRENT =
            new ThreadLocal<InMemoryProfileFacility>();

    private ProfileFieldStoreLocator() {
        // utility
    }

    /** Bind the facility for the current thread / container scope. */
    public static void set(InMemoryProfileFacility facility) {
        if (facility == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(facility);
        }
    }

    /**
     * Lookup the currently bound facility, or {@code null} if none.
     *
     * @return the active facility, or {@code null} when the container
     *         has not been started (or has been stopped)
     */
    public static InMemoryProfileFacility get() {
        return CURRENT.get();
    }
}