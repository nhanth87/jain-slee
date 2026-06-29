/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.recovery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sprint S7 - immutable value object that captures everything the kernel needs
 * to resurrect an SBB entity that has been recycled while an inbound event was
 * still in flight.
 *
 * @author Tran Nhan (nhanth87)
 */
public record RecoverySnapshot(
        String entityId,
        String sbbClassFqn,
        Map<String, Object> cmpFields,
        Set<String> attachedAciNames,
        long capturedAtMs,
        String convergenceKey
) {

    /**
     * Compact canonical constructor - defensively copies the CMP map and ACI
     * set so the snapshot is safe to share across threads and across the
     * (possibly concurrent) rehydration path.
     */
    public RecoverySnapshot {
        cmpFields = cmpFields == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(cmpFields));
        attachedAciNames = attachedAciNames == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(attachedAciNames));
    }

    /**
     * Convenience factory that drops the convergence key (used when IES is
     * not bound or the entity has no convergence mapping).
     */
    public static RecoverySnapshot of(String entityId,
                                      String sbbClassFqn,
                                      Map<String, Object> cmpFields,
                                      Set<String> attachedAciNames,
                                      long capturedAtMs) {
        return new RecoverySnapshot(entityId, sbbClassFqn, cmpFields,
                attachedAciNames, capturedAtMs, null);
    }
}
