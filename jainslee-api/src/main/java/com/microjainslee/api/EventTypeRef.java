package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §5.1 — Event Type Reference.
 * Immutable reference to an Event Type.
 */
public final class EventTypeRef {
    private final String name;
    private final String vendor;
    private final String version;

    public EventTypeRef(String name, String vendor, String version) {
        this.name = name;
        this.vendor = vendor;
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public String getVendor() {
        return vendor;
    }

    public String getVersion() {
        return version;
    }
}