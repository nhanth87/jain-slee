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

import java.util.Map;

/**
 * JAIN-SLEE 1.1 §6.5 — Container-Managed Persistence backing store.
 *
 * <p>Stores the per-entity CMP state (a map of {@code fieldName → value})
 * keyed by entity ID. Implementations are responsible for durability;
 * {@link InMemoryCmpFieldStore} is the default single-JVM backend used
 * for R&D and tests; production deployments can plug in a Redis- or
 * JPA-backed implementation without changing the call sites.
 *
 * <p>All methods MUST be thread-safe.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface CmpFieldStore {

    /**
     * Load the CMP state of the entity.
     *
     * <p>Returns an empty map (never {@code null}) when the entity has
     * never been written. Implementations are encouraged to return a
     * defensive copy so the caller cannot mutate the stored state.
     *
     * @param entityId the SBB entity id
     * @return the CMP state; empty map when no state has been stored
     */
    Map<String, Object> load(String entityId);

    /**
     * Persist the CMP state of the entity. Passing an empty map is
     * equivalent to {@link #remove(String)}.
     *
     * @param entityId the SBB entity id
     * @param state    the CMP state to persist; must not be {@code null}
     */
    void store(String entityId, Map<String, Object> state);

    /**
     * Drop all CMP state for the entity. Idempotent.
     *
     * @param entityId the SBB entity id
     */
    void remove(String entityId);

    /**
     * Check whether the entity has any stored CMP state.
     *
     * @param entityId the SBB entity id
     * @return {@code true} when at least one field is stored
     */
    boolean contains(String entityId);
}