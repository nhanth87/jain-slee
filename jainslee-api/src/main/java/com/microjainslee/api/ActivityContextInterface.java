package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §6 — Activity Context Interface.
 * Represents an Activity Context in the SLEE.
 */
public interface ActivityContextInterface {
    String getActivityContextName();
    void attach(SbbLocalObject sbbLocalObject);
    void detach(SbbLocalObject sbbLocalObject);
}