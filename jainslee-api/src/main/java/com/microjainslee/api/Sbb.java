package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §8.4 — SBB Lifecycle interface.
 * All SBBs must implement this interface.
 */
public interface Sbb {
    default void sbbCreate() {}
    default void sbbActivate() {}
    default void sbbPassivate() {}
    default void sbbRemove() {}
    default void sbbLoad() {}
    default void sbbStore() {}
    default void sbbExceptionThrown(Exception e, Object event, ActivityContextInterface aci) {}
    default void setSbbContext(SbbContext context) {}
}