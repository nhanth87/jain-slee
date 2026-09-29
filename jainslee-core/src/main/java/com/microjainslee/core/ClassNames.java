/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

/**
 * ADR 0007 D7 / P4-e — cached {@code Class#getSimpleName()}.
 *
 * <p>
 * The event router called {@code getSimpleName()} twice per delivery (once for
 * the MDC event-type field, once for a debug log). On JDK 25 the reflective
 * {@code getSimpleName()} path still allocates a fresh {@code String} on each
 * call — the JDK caches the {@code SimpleName} object internally in some
 * versions but the {@code String} it returns is not shared across unrelated
 * callers — so this was two string allocations on the hottest path in the SLEE.
 *
 * <p>
 * {@link ClassValue} is the right cache here: it is keyed on the class itself,
 * participates in class unloading (no classloader leak), and needs no eviction
 * policy. A {@code ConcurrentHashMap} would pin classes from redeployed
 * application classloaders — a real leak in a Quarkus dev loop.
 */
final class ClassNames {

    private static final ClassValue<String> SIMPLE = new ClassValue<String>() {
        @Override
        protected String computeValue(Class<?> type) {
            String name = type.getSimpleName();
            // Anonymous classes have an empty simple name; fall back so log
            // correlation is never blank.
            return (name == null || name.isEmpty()) ? type.getName() : name;
        }
    };

    private ClassNames() {
        // utility
    }

    /** @return the cached simple name; never null, never empty. */
    static String simpleName(Class<?> type) {
        if (type == null) {
            return "?";
        }
        return SIMPLE.get(type);
    }
}
