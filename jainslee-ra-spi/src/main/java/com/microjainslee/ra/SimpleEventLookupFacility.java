/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra;

import com.microjainslee.api.EventLookupFacility;
import com.microjainslee.api.EventTypeId;
import com.microjainslee.api.EventType;
import com.microjainslee.api.FireableEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Perfect Core S5 — in-memory {@link EventLookupFacility} used when an
 * RA is wired without a deployment-descriptor-backed event registry.
 * Events are registered at runtime via
 * {@link #registerEventType(EventTypeId, Class)}; the lookup returns a
 * simple {@link FireableEventType} handle.
 */
public final class SimpleEventLookupFacility implements EventLookupFacility {

    public static final SimpleEventLookupFacility INSTANCE = new SimpleEventLookupFacility();

    private final Map<String, Entry> byKey = new HashMap<>();

    public SimpleEventLookupFacility registerEventType(EventTypeId id, Class<?> eventClass) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        synchronized (byKey) {
            byKey.put(keyOf(id), new Entry(id, eventClass));
        }
        return this;
    }

    @Override
    public FireableEventType getFireableEventType(EventTypeId id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        Entry e;
        synchronized (byKey) {
            e = byKey.get(keyOf(id));
        }
        if (e == null) {
            throw new IllegalArgumentException("Unknown EventTypeId: " + id);
        }
        return new SimpleFireableEventType(e);
    }

    @Override
    public Iterable<EventTypeId> getAllEventTypeIds() {
        synchronized (byKey) {
            return Collections.unmodifiableList(
                    byKey.values().stream().map(x -> x.id).toList());
        }
    }

    private static String keyOf(EventTypeId id) {
        return id.getName() + "|" + id.getVendor() + "|" + id.getVersion();
    }

    private record Entry(EventTypeId id, Class<?> eventClass) implements EventType {
        @Override public EventTypeId getId() { return id; }
        @Override public String getName() { return id.getName(); }
        @Override public String getVendor() { return id.getVendor(); }
        @Override public String getVersion() { return id.getVersion(); }
        @Override public Class<?> getEventClass() { return eventClass; }
    }

    private record SimpleFireableEventType(EventType eventType) implements FireableEventType {
        @Override public EventType getEventType() { return eventType; }
        @Override public String getName() { return eventType.getName(); }
    }
}
