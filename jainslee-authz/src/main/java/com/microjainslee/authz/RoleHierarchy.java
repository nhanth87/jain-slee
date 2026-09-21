/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.authz;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Directed "senior role includes junior role" graph.
 *
 * <p>{@code parse("platform-admin > tenant-admin > user")} makes a
 * platform-admin also a tenant-admin and a user. Several chains may be joined
 * with {@code ;}, e.g. {@code "a > b > c; auditor > c"}. Cycles are rejected.
 */
public final class RoleHierarchy {

    /** {@code platform-admin > tenant-admin > user}. */
    public static final RoleHierarchy DEFAULT =
            parse(Roles.PLATFORM_ADMIN + " > " + Roles.TENANT_ADMIN + " > " + Roles.USER);

    private final Map<String, Set<String>> closure;

    private RoleHierarchy(Map<String, Set<String>> directJuniors) {
        Map<String, Set<String>> c = new HashMap<>();
        for (String role : directJuniors.keySet()) {
            c.put(role, Set.copyOf(reach(role, directJuniors)));
        }
        this.closure = Map.copyOf(c);
    }

    public static RoleHierarchy parse(String spec) {
        Map<String, Set<String>> juniors = new HashMap<>();
        for (String chain : spec.split(";")) {
            String[] parts = chain.split(">");
            for (int i = 0; i < parts.length; i++) {
                String role = parts[i].trim();
                if (role.isEmpty()) {
                    throw new IllegalArgumentException("empty role in hierarchy: " + spec);
                }
                juniors.computeIfAbsent(role, r -> new LinkedHashSet<>());
                if (i + 1 < parts.length) {
                    juniors.get(role).add(parts[i + 1].trim());
                }
            }
        }
        for (String role : juniors.keySet()) {
            if (reach(role, juniors).contains(role)) {
                throw new IllegalArgumentException("role hierarchy has a cycle through " + role);
            }
        }
        return new RoleHierarchy(juniors);
    }

    /** Roles strictly reachable from {@code role} (excluding itself unless cyclic). */
    private static Set<String> reach(String role, Map<String, Set<String>> juniors) {
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(juniors.getOrDefault(role, Set.of()));
        while (!todo.isEmpty()) {
            String r = todo.pop();
            if (seen.add(r)) {
                todo.addAll(juniors.getOrDefault(r, Set.of()));
            }
        }
        return seen;
    }

    /** Held roles plus every role they include. */
    public Set<String> expand(Collection<String> held) {
        Set<String> out = new LinkedHashSet<>(held);
        for (String r : held) {
            out.addAll(closure.getOrDefault(r, Set.of()));
        }
        return out;
    }

    /** True when any held role equals or includes {@code required}. */
    public boolean satisfies(Collection<String> held, String required) {
        for (String r : held) {
            if (r.equals(required) || closure.getOrDefault(r, Set.of()).contains(required)) {
                return true;
            }
        }
        return false;
    }
}
