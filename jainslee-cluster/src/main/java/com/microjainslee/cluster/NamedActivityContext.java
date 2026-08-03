/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SbbLocalObject;

import java.io.Serializable;
import java.util.Objects;

/**
 * Lightweight, marshallable ACI handle stored / reconstructed by
 * {@link ClusteredActivityContextNamingFacility} for cross-node lookups.
 *
 * <p>Attach/detach are no-ops: the live SBB attachment graph stays node-local.
 * Peers use the activity-context name as the distributed identity.
 */
public final class NamedActivityContext implements ActivityContextInterface, Serializable {

    private static final long serialVersionUID = 1L;

    private final String activityContextName;

    public NamedActivityContext(String activityContextName) {
        this.activityContextName = Objects.requireNonNull(activityContextName, "activityContextName");
    }

    @Override
    public String getActivityContextName() {
        return activityContextName;
    }

    @Override
    public void attach(SbbLocalObject sbbLocalObject) {
        // Node-local only — clustered naming distributes the name, not the graph.
    }

    @Override
    public void detach(SbbLocalObject sbbLocalObject) {
        // Node-local only.
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NamedActivityContext that)) {
            return false;
        }
        return activityContextName.equals(that.activityContextName);
    }

    @Override
    public int hashCode() {
        return activityContextName.hashCode();
    }

    @Override
    public String toString() {
        return "NamedActivityContext[" + activityContextName + "]";
    }
}
