/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

import java.util.Map;

/**
 * SPI for row-level profile persistence (Phase 2 write-behind layer).
 *
 * <p>Implementations of this interface back the in-memory hot store
 * ({@code InMemoryProfileFacility}) as a durable persistence layer.
 * All methods operate on a {@link Map} of CMP field values whose value
 * types MUST conform to the JDK-only whitelist defined in Contract C7:
 * {@code String}, boxed primitives, {@code byte[]}, or
 * {@code List} / {@code Map} / {@code Set} of those types.
 * App enums must be stored as {@code name()}; POJOs as JSON strings.
 *
 * <h2>Thread-safety contract</h2>
 * Implementations MUST be thread-safe. The write-behind flusher
 * calls {@link #storeFields} / {@link #remove} from a single daemon
 * virtual thread (C6.3), but embedders that call these methods directly
 * from management code must be prepared for concurrent access.
 *
 * <h2>Blocking-IO rule (C6)</h2>
 * All operations on this interface MAY block (they run on the
 * write-behind VT, never on a Vert.x event-loop or Disruptor worker
 * thread).  Implementations MUST NOT be called from SBB event handlers
 * or RA callbacks.
 *
 * @author Tran Nhan (nhanth87)
 * @see DurableProfileStore
 * @see ProfileMutation
 */
public interface ProfileStore {

    /**
     * Load all CMP field values for a profile row.
     *
     * @param id profile identifier (must not be {@code null})
     * @return the field map, or {@code null} when the row does not exist
     *         in this store
     * @throws NullPointerException if {@code id} is {@code null}
     */
    Map<String, Object> loadFields(ProfileID id);

    /**
     * Persist (create or replace) all CMP field values for a profile row.
     * The supplied map must contain only C7-legal value types.
     *
     * @param id     profile identifier (must not be {@code null})
     * @param fields field snapshot to persist (must not be {@code null})
     * @throws NullPointerException if either argument is {@code null}
     */
    void storeFields(ProfileID id, Map<String, Object> fields);

    /**
     * Remove a profile row from this store. A no-op when the row
     * does not exist.
     *
     * @param id profile identifier (must not be {@code null})
     * @throws NullPointerException if {@code id} is {@code null}
     */
    void remove(ProfileID id);
}
