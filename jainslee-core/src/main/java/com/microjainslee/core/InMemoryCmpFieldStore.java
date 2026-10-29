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

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link CmpFieldStore} backed by a process-local
 * {@link ConcurrentHashMap}. Suitable for unit tests, single-JVM R&D,
 * and the embedded Quarkus/Spring use cases that micro-jainslee targets.
 *
 * <p>Defensive copies are returned from {@link #load(String)} so a
 * caller mutating the result cannot corrupt the stored state. Stored
 * values are not defensively copied — callers are expected to treat
 * the map they hand to {@link #store(String, Map)} as a value object
 * and not mutate it after handing it over.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class InMemoryCmpFieldStore implements CmpFieldStore {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> store =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>();

    @Override
    public Map<String, Object> load(String entityId) {
        if (entityId == null) {
            return Collections.emptyMap();
        }
        ConcurrentHashMap<String, Object> state = store.get(entityId);
        if (state == null || state.isEmpty()) {
            return Collections.emptyMap();
        }
        return new HashMap<String, Object>(state);
    }

    @Override
    public void store(String entityId, Map<String, Object> state) {
        if (entityId == null) {
            throw new IllegalArgumentException("entityId is required");
        }
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (state.isEmpty()) {
            store.remove(entityId);
            return;
        }
        ConcurrentHashMap<String, Object> copy = new ConcurrentHashMap<String, Object>();
        for (Map.Entry<String, Object> e : state.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            copy.put(e.getKey(), defensivelyCopyIfSerializable(e.getValue()));
        }
        store.put(entityId, copy);
    }

    @Override
    public void remove(String entityId) {
        if (entityId == null) {
            return;
        }
        store.remove(entityId);
    }

    @Override
    public boolean contains(String entityId) {
        if (entityId == null) {
            return false;
        }
        ConcurrentHashMap<String, Object> state = store.get(entityId);
        return state != null && !state.isEmpty();
    }

    /** Total number of entities currently tracked (for diagnostics + tests). */
    public int size() {
        return store.size();
    }

    /** Drop all entities. */
    public void clear() {
        store.clear();
    }

    /** Public no-arg {@code clone()} per class, or {@code null}; looked up once per class. */
    private static final ClassValue<java.lang.reflect.Method> CLONE = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Method computeValue(Class<?> type) {
            try {
                return type.getMethod("clone");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    };

    private static Object defensivelyCopyIfSerializable(Object value) {
        if (value == null) {
            return null;
        }
        // Immutable values are already pass-by-value (JSR 240 §6.5.4) — share them.
        // String has no public clone(): looking it up threw NoSuchMethodException on
        // every CMP write (2.5 % of a 120 CPS profile).
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?> || value instanceof Record) {
            return value;
        }
        switch (value) {
            case byte[] a -> { return a.clone(); }
            case int[] a -> { return a.clone(); }
            case long[] a -> { return a.clone(); }
            case Object[] a -> { return a.clone(); }
            default -> { }
        }
        if (value instanceof Serializable) {
            // Best-effort deep copy via clone(). For most CMP scalar types
            // (ArrayList of strings, HashMap of primitives) clone() is sufficient. We
            // avoid pulling in a deep-copy library to keep the runtime footprint tiny.
            java.lang.reflect.Method clone = CLONE.get(value.getClass());
            if (clone != null) {
                try {
                    Object copy = clone.invoke(value);
                    if (copy != null) {
                        return copy;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // fall through and return the original reference
                }
            }
        }
        return value;
    }
}