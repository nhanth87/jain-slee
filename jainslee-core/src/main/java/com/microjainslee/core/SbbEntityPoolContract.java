/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import java.util.function.Supplier;

import com.microjainslee.api.Sbb;

/**
 * ADR 0007 D4 — the entity-pool contract {@link EventRouter} actually depends on.
 *
 * <h2>Why a separate interface rather than reusing {@code SbbEntityPool}</h2>
 * {@code SbbEntityPool} is a long-standing <b>concrete façade class</b> (a
 * backward-compatibility shim wrapping {@link VirtualThreadSbbEntityPool}). Turning
 * <em>it</em> into the seam would have been a breaking change for every external
 * caller that constructs {@code new SbbEntityPool(min, max, perVt)}. So the
 * interface is separate, and {@code SbbEntityPool} now implements it.
 *
 * <p>
 * What this buys:
 * <ul>
 * <li>{@link VirtualThreadSbbEntityPool} is no longer {@code final}, so
 * {@code DistributedSbbEntityPool} can implement this contract instead of only
 * composing the local pool;</li>
 * <li>{@code EventRouter} holds this interface, so
 * {@code MicroSleeContainer#bindDistributedSbbPool(Object)} can actually rebind
 * the delivery path — which is what makes the replicated
 * {@code sbb-entity-state} cache reachable for node failover.</li>
 * </ul>
 */
public interface SbbEntityPoolContract {

    /**
     * Return the entity bound to {@code sbbId}, creating it from {@code factory}
     * if this is the first request. A cluster-aware pool may hydrate the new
     * entity from a replicated snapshot instead of running user state through
     * {@code sbbCreate}.
     *
     * @param sbbId   entity id — cluster-stable; see ADR 0007 D1
     * @param factory creates a fresh SBB instance when none is cached
     * @return the entity, never {@code null}
     */
    VirtualThreadSbbEntityPool.SbbEntity acquire(String sbbId, Supplier<Sbb> factory);

    /**
     * Bind a pre-built SBB instance (already hydrated from a snapshot) to
     * {@code sbbId}. Used by the cluster pool after applying a snapshot; the hot
     * local path does not need it.
     *
     * @param sbbId   entity id
     * @param entityId legacy numeric entity id, {@code 0} when unused
     * @param sbb     the instance to bind
     * @return the entity, never {@code null}
     */
    VirtualThreadSbbEntityPool.SbbEntity acquire(String sbbId, long entityId, Sbb sbb);

    /**
     * Unbind and recycle {@code entity}. No-op when {@code entity} is null.
     */
    void release(VirtualThreadSbbEntityPool.SbbEntity entity);

    /**
     * @return the local entity, or {@code null} when this pool does not hold it
     */
    VirtualThreadSbbEntityPool.SbbEntity findEntity(String sbbId);

    /**
     * @return number of entities currently held locally
     */
    int size();
}
