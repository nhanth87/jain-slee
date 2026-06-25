package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §6.2 — Activity Context Naming Facility interface.
 * Provides naming facilities for Activity Contexts.
 */
public interface ActivityContextNamingFacility {
    void bind(String name, ActivityContextInterface aci);
}