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

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry of {@link SbbTypePool} instances keyed by concrete SBB class.
 */
public final class SbbTypeRegistry {

    private final ConcurrentHashMap<Class<? extends Sbb>, SbbTypePool> pools =
            new ConcurrentHashMap<Class<? extends Sbb>, SbbTypePool>();
    private final SbbLifecycleManager lifecycleManager;
    private final int defaultMaxActive;

    public SbbTypeRegistry(SbbLifecycleManager lifecycleManager, int defaultMaxActive) {
        this.lifecycleManager = lifecycleManager;
        this.defaultMaxActive = defaultMaxActive;
    }

    public void register(Class<? extends Sbb> type, SbbTypePoolConfig config) {
        if (type == null || config == null) {
            throw new IllegalArgumentException("type and config are required");
        }
        pools.put(type, new SbbTypePool(config, lifecycleManager));
    }

    public void register(Class<? extends Sbb> type, Supplier<Sbb> factory) {
        register(type, SbbTypePoolConfig.builder(factory)
                .maxActive(defaultMaxActive)
                .build());
    }

    public SbbTypePool require(Class<? extends Sbb> type) {
        SbbTypePool pool = pools.get(type);
        if (pool == null) {
            throw new IllegalStateException("SBB type not registered: " + type.getName());
        }
        return pool;
    }

    public SbbTypePool find(Class<? extends Sbb> type) {
        return pools.get(type);
    }

    public boolean isRegistered(Class<? extends Sbb> type) {
        return pools.containsKey(type);
    }

    public boolean isRegistered(Sbb sbb) {
        return sbb != null && pools.containsKey(sbb.getClass());
    }

    /**
     * Drop pools whose type matches the given FQCN or simple name.
     * Used by Quarkus {@code quarkus:dev} live-reload: each reload loads a new
     * {@link Class} identity for the same SBB name; without this, routing can
     * keep borrowing instances from the previous classloader's pool.
     *
     * @return number of pools removed
     */
    public int unregisterByName(String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        int[] removed = {0};
        pools.entrySet().removeIf(e -> {
            Class<? extends Sbb> type = e.getKey();
            if (name.equals(type.getName()) || name.equals(type.getSimpleName())) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    /**
     * GOAL 2 — resolve a registered SBB type by name. Accepts either the
     * fully-qualified class name or the simple class name (the form used
     * by {@code MicroSleeContainer.mapEventToSbb}). Returns {@code null}
     * when no registered type matches.
     */
    public Class<? extends Sbb> findTypeByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Class<? extends Sbb> type : pools.keySet()) {
            if (name.equals(type.getName()) || name.equals(type.getSimpleName())) {
                return type;
            }
        }
        return null;
    }
}
