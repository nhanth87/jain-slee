package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §8.5 — SBB Local Object interface.
 * Represents an SBB instance in an Activity Context.
 */
public interface SbbLocalObject {
    Sbb getSbb();
    SbbID getSbbID();
}