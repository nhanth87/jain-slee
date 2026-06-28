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

import java.util.Collection;

/**
 * JAIN-SLEE 1.1 §6.2 — Activity Context Naming Facility interface.
 * Provides naming facilities for Activity Contexts.
 */
public interface ActivityContextNamingFacility {
    void bind(String name, ActivityContextInterface aci);

    /**
     * Look up the ACI previously bound to the given name.
     * @return the ACI, or {@code null} when none is registered.
     */
    default ActivityContextInterface lookup(String name) {
        throw new UnsupportedOperationException("lookup not supported by this facility");
    }

    /**
     * Remove the binding for the given name. No-op when the name was
     * not bound.
     */
    default void unbind(String name) {
        throw new UnsupportedOperationException("unbind not supported by this facility");
    }

    /**
     * @return an unmodifiable view of all currently bound ACIs. Used
     *         for shutdown sweeps and detach operations.
     */
    default Collection<ActivityContextInterface> getBoundContexts() {
        return java.util.Collections.emptyList();
    }

    /**
     * @return all currently bound ACI names. Used for diagnostics.
     */
    default java.util.Set<String> names() {
        return java.util.Collections.emptySet();
    }

    /**
     * Drop every binding. Best-effort; implementations that do not
     * support bulk removal simply return without error.
     */
    default void clear() {
        // no-op default
    }
}