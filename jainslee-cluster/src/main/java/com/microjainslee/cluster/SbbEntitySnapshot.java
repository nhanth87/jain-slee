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

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Production P2.3 / P3 — POJO snapshot of a single SBB entity's CMP state
 * (+ profile refs) for ISPN HA.
 *
 * <p>Create/delete hot path must <strong>not</strong> write this object;
 * only {@link DistributedSbbEntityPool#checkpoint} (and legacy
 * {@code jainslee.sbb.checkpoint.on-release=true}) persist it.
 *
 * <p>OffHeap CMP is never included — heap {@code @CmpField} only.
 */
public final class SbbEntitySnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String sbbClassFqn;
    private final String sbbId;
    private final Map<String, Object> cmpFieldValues;
    private final Set<String> attachedAciNames;
    private final long snapshotTimestamp;
    /** Monotonic fence for coalesce / LWW. */
    private final long generation;
    /** Profile refs as {@code table/name} (data loaded via ProfileFacility). */
    private final Set<String> profileRefs;

    /**
     * Legacy 5-arg constructor — generation {@code 0}, empty profile refs.
     */
    public SbbEntitySnapshot(String sbbClassFqn,
                              String sbbId,
                              Map<String, Object> cmpFieldValues,
                              Set<String> attachedAciNames,
                              long snapshotTimestamp) {
        this(sbbClassFqn, sbbId, cmpFieldValues, attachedAciNames, snapshotTimestamp, 0L, null);
    }

    public SbbEntitySnapshot(String sbbClassFqn,
                              String sbbId,
                              Map<String, Object> cmpFieldValues,
                              Set<String> attachedAciNames,
                              long snapshotTimestamp,
                              long generation,
                              Set<String> profileRefs) {
        this.sbbClassFqn = Objects.requireNonNull(sbbClassFqn, "sbbClassFqn");
        this.sbbId = Objects.requireNonNull(sbbId, "sbbId");
        this.cmpFieldValues = cmpFieldValues == null || cmpFieldValues.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cmpFieldValues));
        this.attachedAciNames = attachedAciNames == null || attachedAciNames.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(attachedAciNames));
        this.snapshotTimestamp = snapshotTimestamp;
        this.generation = generation;
        this.profileRefs = profileRefs == null || profileRefs.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(profileRefs));
    }

    public String getSbbClassFqn() {
        return sbbClassFqn;
    }

    public String getSbbId() {
        return sbbId;
    }

    public Map<String, Object> getCmpFieldValues() {
        return cmpFieldValues;
    }

    public Set<String> getAttachedAciNames() {
        return attachedAciNames;
    }

    public long getSnapshotTimestamp() {
        return snapshotTimestamp;
    }

    public long getGeneration() {
        return generation;
    }

    /** {@code table/name} refs — ProfileFacility loads values on hydrate. */
    public Set<String> getProfileRefs() {
        return profileRefs;
    }

    @Override
    public String toString() {
        return "SbbEntitySnapshot[sbbClassFqn=" + sbbClassFqn
                + ", sbbId=" + sbbId
                + ", cmpFieldCount=" + cmpFieldValues.size()
                + ", attachedAciCount=" + attachedAciNames.size()
                + ", profileRefCount=" + profileRefs.size()
                + ", generation=" + generation
                + ", snapshotTimestamp=" + snapshotTimestamp
                + ']';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SbbEntitySnapshot that)) {
            return false;
        }
        return snapshotTimestamp == that.snapshotTimestamp
                && generation == that.generation
                && sbbClassFqn.equals(that.sbbClassFqn)
                && sbbId.equals(that.sbbId)
                && cmpFieldValues.equals(that.cmpFieldValues)
                && attachedAciNames.equals(that.attachedAciNames)
                && profileRefs.equals(that.profileRefs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sbbClassFqn, sbbId, cmpFieldValues,
                attachedAciNames, snapshotTimestamp, generation, profileRefs);
    }
}
