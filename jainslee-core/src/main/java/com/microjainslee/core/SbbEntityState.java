/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-SBB entity runtime state tracked by the container:
 * the current CMP fields, the registered child relations, and the
 * lifecycle state machine pointer.
 *
 * <p>This is the in-memory shadow of what the spec calls an "SBB entity";
 * the {@link Sbb} instance itself is intentionally a POJO and does not
 * carry this state — the container keeps them synchronized via
 * {@link SbbLifecycleManager} (which drives
 * {@link Sbb#sbbLoad() sbbLoad} /
 * {@link Sbb#sbbStore() sbbStore}) and {@link CmpAccessorInvoker}
 * (which reads/writes individual fields).
 *
 * @author Tran Nhan (nhanth87)
 */
public final class SbbEntityState {

    private final Map<String, Object> cmpFields = new ConcurrentHashMap<String, Object>();
    private final List<ChildRelationImpl> childRelations =
            Collections.synchronizedList(new ArrayList<ChildRelationImpl>());
    private volatile SbbLifecycleManager.State lifecycleState =
            SbbLifecycleManager.State.POOLED;
    private volatile boolean removed;

    public Map<String, Object> getCmpFields() {
        return cmpFields;
    }

    public List<ChildRelationImpl> getChildRelations() {
        synchronized (childRelations) {
            return new ArrayList<ChildRelationImpl>(childRelations);
        }
    }

    public void registerChildRelation(ChildRelationImpl relation) {
        if (relation == null) {
            throw new IllegalArgumentException("relation is required");
        }
        childRelations.add(relation);
    }

    public SbbLifecycleManager.State getLifecycleState() {
        return lifecycleState;
    }

    /**
     * Atomic state transition. Volatile write is enough for the read visibility
     * guarantees the JAIN SLEE 1.1 spec requires (single-state-at-a-time
     * observation across the SBB entity thread boundary), but we synchronize
     * to be defensive against concurrent transitions triggered by, e.g.,
     * cascading removal racing with normal passivation.
     */
    public synchronized void transitionTo(SbbLifecycleManager.State next) {
        this.lifecycleState = next;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void markRemoved() {
        this.removed = true;
    }
}