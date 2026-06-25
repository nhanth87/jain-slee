package com.microjainslee.core;

import com.microjainslee.api.*;

/**
 * JAIN-SLEE 1.1 §8.4 — SBB Lifecycle Manager.
 * Manages SBB lifecycle transitions.
 */
public class SbbLifecycleManager {
    public enum State { DOES_NOT_EXIST, POOLED, READY, ACTIVE, PASSIVE }

    public void create(Sbb sbb, SbbContext context) {
        sbb.setSbbContext(context);
        sbb.sbbCreate();
    }

    public void activate(Sbb sbb) {
        sbb.sbbActivate();
    }

    public void passivate(Sbb sbb) {
        sbb.sbbPassivate();
    }

    public void remove(Sbb sbb) {
        sbb.sbbRemove();
    }
}