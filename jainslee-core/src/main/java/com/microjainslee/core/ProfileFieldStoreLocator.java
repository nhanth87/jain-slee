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
 * Locator for the profile CMP field store used by
 * {@link com.microjainslee.api.ProfileAccessorInvoker}.
 *
 * <p>Resolution order: the thread-local binding (tests running several
 * containers on separate threads), then the JVM-global binding. The global
 * fallback is essential — SBB event handlers run on event-router /
 * virtual-thread executors, never on the thread that constructed the
 * container, so a pure {@link ThreadLocal} would make every profile read from
 * an event handler fail.
 *
 * <h3>Phase 2 detachment</h3>
 * <p>The type held by this locator was changed from the concrete
 * {@link InMemoryProfileFacility} to the {@link ProfileFieldAccess} interface.
 * This allows alternative hot-store implementations to be registered without
 * coupling the accessor bridge to a specific class.
 */
public final class ProfileFieldStoreLocator {

    private static final ThreadLocal<ProfileFieldAccess> CURRENT = new ThreadLocal<>();
    private static volatile ProfileFieldAccess global;

    private ProfileFieldStoreLocator() {
        // utility
    }

    /**
     * Bind the field-access store for the current thread AND as the JVM-global
     * default. Passing {@code null} clears the thread-local; the global is only
     * cleared when it points at the same instance being unbound.
     */
    public static void set(ProfileFieldAccess store) {
        if (store == null) {
            ProfileFieldAccess previous = CURRENT.get();
            CURRENT.remove();
            if (previous != null && previous == global) {
                global = null;
            }
        } else {
            CURRENT.set(store);
            global = store;
        }
    }

    /** Thread-local binding first, JVM-global fallback second. */
    public static ProfileFieldAccess get() {
        ProfileFieldAccess local = CURRENT.get();
        return local != null ? local : global;
    }

    /** Explicitly clear the JVM-global binding (container shutdown). */
    static void clearGlobal(ProfileFieldAccess store) {
        if (store == null || store == global) {
            global = null;
        }
    }
}
