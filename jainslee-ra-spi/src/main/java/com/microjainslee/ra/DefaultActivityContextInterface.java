/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SbbLocalObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Perfect Core S5 — minimal {@link ActivityContextInterface} used by
 * {@link SleeEndpointImpl} when the kernel's ACI pool is not wired in.
 * <p>
 * The production code path always delegates to
 * {@code MicroSleeContainer.createActivityContext(name)} so the ACNF
 * lookup still routes events through the Disruptor-backed
 * {@link com.microjainslee.core.EventRouter}. This fallback exists so
 * RAs can be unit-tested without spinning up the whole container.
 */
public class DefaultActivityContextInterface implements ActivityContextInterface {

    private final String name;
    private final String handleId;
    private final CopyOnWriteArrayList<SbbLocalObject> attached = new CopyOnWriteArrayList<>();

    public DefaultActivityContextInterface(String name, String handleId) {
        this.name = name;
        this.handleId = handleId;
    }

    public String getHandleId() { return handleId; }

    @Override
    public String getActivityContextName() { return name; }

    @Override
    public void attach(SbbLocalObject sbbLocalObject) {
        if (sbbLocalObject != null && !attached.contains(sbbLocalObject)) {
            attached.add(sbbLocalObject);
        }
    }

    @Override
    public void detach(SbbLocalObject sbbLocalObject) {
        attached.remove(sbbLocalObject);
    }

    public int attachedCount() { return attached.size(); }

    /** Bulk detach helper used by SleeEndpointImpl when the activity ends. */
    public void detachAll() { attached.clear(); }
}
