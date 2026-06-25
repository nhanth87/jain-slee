package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §8.5 — SBB ID.
 * Unique identifier for an SBB.
 */
public final class SbbID {
    private final String id;

    public SbbID(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}