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

/**
 * JAIN-SLEE 1.1 §6 — three-part event type identifier (name, vendor, version).
 * <p>
 * The micro-jainslee flavour uses the spec's name+vendor+version triplet
 * so event-type identity is portable across vendor boundaries. RAs
 * usually build the id from the event class (name = simple class name)
 * plus the RA entity's vendor/version.
 */
public final class EventTypeId {

    private final String name;
    private final String vendor;
    private final String version;

    public EventTypeId(String name, String vendor, String version) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        if (vendor == null || vendor.isEmpty()) {
            throw new IllegalArgumentException("vendor is required");
        }
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("version is required");
        }
        this.name = name;
        this.vendor = vendor;
        this.version = version;
    }

    public String getName() { return name; }
    public String getVendor() { return vendor; }
    public String getVersion() { return version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventTypeId)) return false;
        EventTypeId that = (EventTypeId) o;
        return name.equals(that.name) && vendor.equals(that.vendor) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + vendor.hashCode();
    }

    @Override
    public String toString() {
        return "EventTypeId[" + name + "," + vendor + "," + version + "]";
    }
}
