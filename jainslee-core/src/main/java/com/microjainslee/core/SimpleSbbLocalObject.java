package com.microjainslee.core;

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;

/**
 * Simple immutable SBB local object for embedded deployments.
 */
public final class SimpleSbbLocalObject implements SbbLocalObject {

    private final SbbID sbbID;
    private final Sbb sbb;

    public SimpleSbbLocalObject(SbbID sbbID, Sbb sbb) {
        if (sbbID == null || sbb == null) {
            throw new IllegalArgumentException("sbbID and sbb are required");
        }
        this.sbbID = sbbID;
        this.sbb = sbb;
    }

    @Override
    public Sbb getSbb() {
        return sbb;
    }

    @Override
    public SbbID getSbbID() {
        return sbbID;
    }
}
