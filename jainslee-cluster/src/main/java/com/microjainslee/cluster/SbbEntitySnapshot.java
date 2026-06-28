/*
 * micro-jainslee 1.1.0
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
 * Production P2.3 - POJO snapshot of a single SBB entity's CMP state.
 *
 * <p>This is the wire format that {@link DistributedSbbEntityPool}
 * pushes into the {@code "sbb-entity-state"} Infinispan cache when an
 * entity is released and uses to reconstruct the entity on a peer node
 * when it is acquired on a node that does not have it in its local pool.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@link #sbbClassFqn} - fully-qualified class name of the SBB.
 *       Used as a sanity check when applying a snapshot back to a fresh
 *       SBB instance so we never silently cross-populate unrelated SBB
 *       classes.</li>
 *   <li>{@link #sbbId} - the logical SBB entity id (same string used as
 *       the Infinispan cache key).</li>
 *   <li>{@link #cmpFieldValues} - map of {@code @CmpField} name to
 *       Java value. Values must be {@link Serializable}; primitives are
 *       auto-boxed. The map is kept as a defensive copy so the snapshot
 *       is safe to share between threads and JVMs.</li>
 *   <li>{@link #attachedAciNames} - the set of activity context names
 *       the SBB entity was attached to at the moment of the snapshot.
 *       The actual {@code ActivityContextInterface} instances are
 *       <b>not</b> stored here - they live in the
 *       {@link ClusteredActivityContextNamingFacility} (P2.2). The
 *       snapshot only carries the names so the receiving node can
 *       re-attach after reconstruction.</li>
 *   <li>{@link #snapshotTimestamp} - wall-clock millis at the moment
 *       the snapshot was taken. Useful for diagnostics and for
 *       last-write-wins conflict resolution if multiple nodes ever
 *       write the same {@code sbbId} concurrently (not expected under
 *       {@code REPL_ASYNC} ownership but tracked for observability).</li>
 * </ul>
 *
 * <h2>Serialization</h2>
 * The class is a plain POJO implementing {@link Serializable} so the
 * default Infinispan Java-serialization marshaller can transport it
 * across the JGroups wire without any custom marshaller wiring. The
 * {@code serialVersionUID} is explicit and bumped when the field
 * shape changes.
 *
 * <p><b>R&amp;D only - never for production.</b>
 */
public final class SbbEntitySnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String sbbClassFqn;
    private final String sbbId;
    private final Map<String, Object> cmpFieldValues;
    private final Set<String> attachedAciNames;
    private final long snapshotTimestamp;

    /**
     * Build a snapshot. Defensive copies are taken so the caller may
     * freely mutate its input structures after this constructor
     * returns without affecting the snapshot.
     *
     * @param sbbClassFqn       fully-qualified SBB class name (must be non-null)
     * @param sbbId             SBB entity id, also used as the cache key
     *                          (must be non-null)
     * @param cmpFieldValues    map of {@code @CmpField} name to value;
     *                          may be {@code null} or empty when the SBB
     *                          has no CMP state yet
     * @param attachedAciNames  set of attached ACI names; may be
     *                          {@code null} or empty when the SBB is
     *                          not attached to any activity context
     * @param snapshotTimestamp wall-clock millis (typically
     *                          {@link System#currentTimeMillis()})
     */
    public SbbEntitySnapshot(String sbbClassFqn,
                              String sbbId,
                              Map<String, Object> cmpFieldValues,
                              Set<String> attachedAciNames,
                              long snapshotTimestamp) {
        this.sbbClassFqn = Objects.requireNonNull(sbbClassFqn, "sbbClassFqn");
        this.sbbId = Objects.requireNonNull(sbbId, "sbbId");
        // Defensive copies + immutable views. We deliberately use
        // LinkedHashMap / LinkedHashSet so the iteration order is stable
        // for the round-trip test and so it matches the order in which
        // @CmpField annotations are discovered by the reflective scan.
        this.cmpFieldValues = cmpFieldValues == null || cmpFieldValues.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cmpFieldValues));
        this.attachedAciNames = attachedAciNames == null || attachedAciNames.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(attachedAciNames));
        this.snapshotTimestamp = snapshotTimestamp;
    }

    /** @return the SBB class fully-qualified name. */
    public String getSbbClassFqn() {
        return sbbClassFqn;
    }

    /** @return the SBB entity id (also the Infinispan cache key). */
    public String getSbbId() {
        return sbbId;
    }

    /**
     * @return an unmodifiable map of {@code @CmpField} name to Java
     *         value. Iteration order matches the order in which the
     *         reflective scan discovered the annotated accessors.
     */
    public Map<String, Object> getCmpFieldValues() {
        return cmpFieldValues;
    }

    /**
     * @return an unmodifiable set of activity context names attached
     *         to the SBB entity at the moment of the snapshot.
     */
    public Set<String> getAttachedAciNames() {
        return attachedAciNames;
    }

    /** @return wall-clock millis at the moment the snapshot was taken. */
    public long getSnapshotTimestamp() {
        return snapshotTimestamp;
    }

    @Override
    public String toString() {
        return "SbbEntitySnapshot[sbbClassFqn=" + sbbClassFqn
                + ", sbbId=" + sbbId
                + ", cmpFieldCount=" + cmpFieldValues.size()
                + ", attachedAciCount=" + attachedAciNames.size()
                + ", snapshotTimestamp=" + snapshotTimestamp
                + ']';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SbbEntitySnapshot)) return false;
        SbbEntitySnapshot that = (SbbEntitySnapshot) o;
        return snapshotTimestamp == that.snapshotTimestamp
                && sbbClassFqn.equals(that.sbbClassFqn)
                && sbbId.equals(that.sbbId)
                && cmpFieldValues.equals(that.cmpFieldValues)
                && attachedAciNames.equals(that.attachedAciNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sbbClassFqn, sbbId, cmpFieldValues,
                attachedAciNames, snapshotTimestamp);
    }
}
