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

import com.microjainslee.api.SleeEvent;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link EventMask} (JSR-240 §8.6).
 */
public class EventMaskTest {

    /** Marker event type used to verify accept filtering. */
    private static final class AlphaEvent implements SleeEvent { }

    private static final class BetaEvent implements SleeEvent { }

    @Test
    public void acceptAllMatchesEverything() {
        assertTrue("ACCEPT_ALL must match any event",
                EventMask.ACCEPT_ALL.matches(new AlphaEvent()));
        assertTrue(EventMask.ACCEPT_ALL.matches(new BetaEvent()));
        assertTrue(EventMask.ACCEPT_ALL.matches(new SleeEvent() { }));
    }

    @Test
    public void emptyMaskMatchesNothing() {
        assertFalse("EMPTY must reject any event",
                EventMask.EMPTY.matches(new AlphaEvent()));
        assertFalse(EventMask.EMPTY.matches(new BetaEvent()));
    }

    @Test
    public void emptyMaskRejectsNull() {
        assertFalse(EventMask.EMPTY.matches(null));
        assertFalse(EventMask.ACCEPT_ALL.matches(null));
    }

    @Test
    public void emptySingletonIsReused() {
        assertSame("EMPTY must be a singleton", EventMask.EMPTY, EventMask.EMPTY);
        assertSame("ACCEPT_ALL must be a singleton",
                EventMask.ACCEPT_ALL, EventMask.ACCEPT_ALL);
    }

    @Test
    public void filterAcceptsListedClass() {
        EventMask mask = new EventMask(AlphaEvent.class);
        assertTrue(mask.matches(new AlphaEvent()));
        assertFalse(mask.matches(new BetaEvent()));
    }

    @Test
    public void filterAcceptsMultipleListedClasses() {
        EventMask mask = new EventMask(AlphaEvent.class, BetaEvent.class);
        assertTrue(mask.matches(new AlphaEvent()));
        assertTrue(mask.matches(new BetaEvent()));
        assertFalse(mask.matches(new SleeEvent() { }));
    }

    @Test
    public void filterFromCollection() {
        EventMask mask = new EventMask(Arrays.<Class<?>>asList(AlphaEvent.class));
        assertTrue(mask.matches(new AlphaEvent()));
        assertFalse(mask.matches(new BetaEvent()));
    }

    @Test
    public void emptyVarargsTreatedAsAcceptAll() {
        // Passing no types must NOT silently disable event delivery.
        EventMask mask = new EventMask(new Class<?>[0]);
        assertTrue("empty varargs must default to ACCEPT_ALL semantics",
                mask.isAcceptAll());
        assertTrue(mask.matches(new AlphaEvent()));
    }

    @Test
    public void defaultConstructorIsAcceptAll() {
        EventMask mask = new EventMask();
        assertTrue(mask.isAcceptAll());
        assertTrue(mask.matches(new AlphaEvent()));
    }

    @Test
    public void isEmptyAndSizeReport() {
        assertTrue(EventMask.EMPTY.isEmpty());
        assertFalse(EventMask.ACCEPT_ALL.isEmpty());

        assertEquals(0, EventMask.EMPTY.size());
        assertEquals(Integer.MAX_VALUE, EventMask.ACCEPT_ALL.size());

        EventMask mask = new EventMask(AlphaEvent.class, BetaEvent.class);
        assertFalse(mask.isEmpty());
        assertFalse(mask.isAcceptAll());
        assertEquals(2, mask.size());
    }

    @Test
    public void rawAcceptedReturnsNullForAcceptAll() {
        assertNull("ACCEPT_ALL must report null allow-list",
                EventMask.ACCEPT_ALL.rawAccepted());
    }

    @Test
    public void rawAcceptedReturnsUnderlyingSetForFilter() {
        EventMask mask = new EventMask(AlphaEvent.class);
        assertTrue(mask.rawAccepted().contains(AlphaEvent.class));
        assertFalse(mask.rawAccepted().contains(BetaEvent.class));
    }

    @Test
    public void nullEntriesInVarargsAreIgnored() {
        EventMask mask = new EventMask(null, AlphaEvent.class, null);
        assertTrue(mask.matches(new AlphaEvent()));
        assertEquals(1, mask.size());
    }

    @Test
    public void toStringIsStable() {
        assertEquals("EventMask[ACCEPT_ALL]", EventMask.ACCEPT_ALL.toString());
        assertEquals("EventMask[EMPTY]", EventMask.EMPTY.toString());
        String s = new EventMask(AlphaEvent.class).toString();
        assertTrue("toString must mention 'filter' for FILTER mode: " + s,
                s.startsWith("EventMask[filter="));
    }
}