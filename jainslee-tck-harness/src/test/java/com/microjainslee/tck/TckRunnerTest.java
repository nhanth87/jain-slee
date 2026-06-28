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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity tests for the {@link TckRunner} skeleton.
 *
 * <p>P1.3 ships the runner as a stub — these tests verify that the
 * stub is at least well-formed: the constructor rejects null input,
 * {@link TckRunner#listGroups()} returns the canonical group set,
 * {@link TckRunner#runGroup(String)} returns an empty list for any
 * known group, and the null/empty guard on the group name fires.
 */
class TckRunnerTest {

    /** A fake adapter is enough — the skeleton does not touch it. */
    private static MicrojainsleeContainerAdapter newAdapter() {
        return new MicrojainsleeContainerAdapter(new Object());
    }

    @Test
    void instantiatesWithNonNullAdapter() {
        TckRunner runner = new TckRunner(newAdapter());
        assertNotNull(runner,
                "runner must be non-null after successful construction");
    }

    @Test
    void rejectsNullAdapter() {
        assertThrows(IllegalArgumentException.class,
                () -> new TckRunner(null),
                "constructor must reject a null adapter argument");
    }

    @Test
    void exposesAdapterByReference() {
        MicrojainsleeContainerAdapter adapter = newAdapter();
        TckRunner runner = new TckRunner(adapter);
        assertSame(adapter, runner.getAdapter(),
                "getAdapter() must return the exact instance passed in");
    }

    @Test
    void listGroupsReturnsCanonicalEnumeration() {
        TckRunner runner = new TckRunner(newAdapter());
        Set<String> groups = runner.listGroups();
        assertNotNull(groups, "listGroups() must never return null");
        assertFalse(groups.isEmpty(),
                "skeleton must expose the canonical group enumeration");
        // Spot-check three canonical entries from the production roadmap.
        assertTrue(groups.contains("EventRouting"),
                "EventRouting is a canonical group");
        assertTrue(groups.contains("SbbLifecycle"),
                "SbbLifecycle is a canonical group");
        assertTrue(groups.contains("Timer"),
                "Timer is a canonical group");
    }

    @Test
    void runGroupReturnsEmptyListForKnownGroup() {
        TckRunner runner = new TckRunner(newAdapter());
        List<DynamicTest> tests = runner.runGroup("EventRouting");
        assertNotNull(tests, "runGroup() must never return null");
        assertTrue(tests.isEmpty(),
                "skeleton runGroup() must return an empty list — P1.5 fills it in");
    }

    @Test
    void runGroupRejectsNullAndEmpty() {
        TckRunner runner = new TckRunner(newAdapter());
        assertThrows(IllegalArgumentException.class,
                () -> runner.runGroup(null),
                "runGroup() must reject null");
        assertThrows(IllegalArgumentException.class,
                () -> runner.runGroup(""),
                "runGroup() must reject empty string");
        assertThrows(IllegalArgumentException.class,
                () -> runner.runGroup("   "),
                "runGroup() must reject whitespace-only string");
    }

    @Test
    void canonicalGroupsConstantIsFrozen() {
        // The canonical set is exposed as a public constant — make sure
        // it matches what listGroups() returns so a downstream test can
        // rely on either entry point interchangeably.
        assertEquals(TckRunner.CANONICAL_GROUPS, new TckRunner(newAdapter()).listGroups(),
                "listGroups() must return the same set as CANONICAL_GROUPS");
    }
}
