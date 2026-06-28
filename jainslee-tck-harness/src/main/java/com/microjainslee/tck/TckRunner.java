/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.tck;

import org.junit.jupiter.api.DynamicTest;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * P1.3 — Skeleton JUnit 5 dynamic-test entry point that runs a TCK group
 * against a {@link MicrojainsleeContainerAdapter}.
 *
 * <p><strong>This is a skeleton.</strong> The {@link #runGroup(String)}
 * method today returns an empty list and {@link #listGroups()} returns
 * an empty set. P1.5 / P1.6 will replace the stubs with real discovery
 * logic that walks the JSR-240 TCK test classpath, groups tests by
 * their {@code @TestGroup} annotation, and returns a {@link DynamicTest}
 * per test case.
 *
 * <p>Design notes:
 * <ul>
 *   <li>JUnit 5's {@code DynamicTest} API is used instead of static
 *       {@code @Test} methods so we don't have to recompile the
 *       harness every time the TCK source tree moves.</li>
 *   <li>The runner takes the adapter in its constructor and holds it
 *       for the lifetime of the JUnit suite — the adapter wraps the
 *       live container and is therefore stateful.</li>
 *   <li>The list of groups returned by {@link #listGroups()} is the
 *       canonical TCK group enumeration: {@code EventRouting},
 *       {@code SbbLifecycle}, {@code Timer}, etc. (see
 *       docs/micro-jainslee-production-roadmap.md §15).</li>
 * </ul>
 */
public class TckRunner {

    /**
     * Canonical TCK group identifiers — these mirror the JSR-240 TCK
     * top-level test groups we plan to validate against. Today they
     * are exposed as a static set so tests can assert the enumeration
     * is non-empty without calling {@link #listGroups()}.
     */
    public static final Set<String> CANONICAL_GROUPS = Set.of(
            "EventRouting",
            "SbbLifecycle",
            "Timer",
            "Profile",
            "ResourceAdaptor",
            "ActivityContext",
            "Naming",
            "Alarm",
            "Trace");

    private final MicrojainsleeContainerAdapter adapter;

    /**
     * Create a runner wired against the supplied adapter. The adapter
     * is held by reference; never null.
     */
    public TckRunner(MicrojainsleeContainerAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "adapter is required (got null)");
        }
        this.adapter = adapter;
    }

    /**
     * Return the adapter this runner was constructed with. Exposed so
     * TCK tests can reach back to the wrapped kernel when they need
     * to install fixtures.
     */
    public MicrojainsleeContainerAdapter getAdapter() {
        return adapter;
    }

    /**
     * Discover all TCK test groups currently known to the harness.
     *
     * <p>TODO (P1.5): scan {@code META-INF/microjainslee/tck-groups}
     * for a SPI file that enumerates the groups contributed by the
     * TCK classpath on the runner's class loader. For now returns
     * {@link #CANONICAL_GROUPS} so the enumeration is non-empty in
     * skeleton form.
     *
     * @return the immutable set of TCK group names (never null,
     *         may be empty if no TCK classpath is on the runner).
     */
    public Set<String> listGroups() {
        // Skeleton implementation — P1.5 will replace this with
        // real ServiceLoader / SPI-based discovery.
        return Collections.unmodifiableSet(CANONICAL_GROUPS);
    }

    /**
     * Build the dynamic-test list for a single TCK group.
     *
     * <p>TODO (P1.5): walk the TCK test classpath, find every test
     * class annotated with {@code @TestGroup(group)}, wrap each
     * {@code @Test} method in a {@link DynamicTest} and return the
     * list. For now returns an empty list so callers can detect the
     * "skeleton" state and short-circuit.
     *
     * @param groupName the TCK group to run (e.g. {@code "EventRouting"}).
     * @return the list of dynamic tests for this group. Empty when
     *         the group is unknown or no TCK classpath is on the
     *         runner (skeleton behaviour).
     */
    public List<DynamicTest> runGroup(String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "groupName is required (got null or empty)");
        }
        // Skeleton: P1.5 will discover tests via reflection / SPI and
        // wrap each @Test method in a DynamicTest. For now we return
        // an empty list so callers can still wire up their suite.
        return Collections.emptyList();
    }
}
