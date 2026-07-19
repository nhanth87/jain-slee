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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Extension of {@link ProfileStore} for backends that support efficient
 * batch mutations (Phase 2 write-behind flusher).
 *
 * <p>The write-behind flusher in {@code InMemoryProfileFacility} collects
 * dirty {@link ProfileID}s since the last flush cycle, builds a list of
 * {@link ProfileMutation}s, and delivers them to this interface in a single
 * call.  Backends that can commit a batch atomically (e.g. a JDBC
 * {@code executeBatch} or an Infinispan {@code putAll}) should implement this
 * interface rather than plain {@link ProfileStore} to minimize round-trips.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>The list contains at most one mutation per {@link ProfileID};
 *       duplicates are coalesced by the flusher (last write wins).</li>
 *   <li>If {@code storeBatch} throws, the flusher logs an error and
 *       re-queues all affected rows for the next flush cycle.</li>
 *   <li>An empty list is a valid (no-op) call; implementations MUST
 *       accept it without error.</li>
 * </ul>
 *
 * @author Tran Nhan (nhanth87)
 * @see ProfileStore
 * @see ProfileMutation
 */
public interface DurableProfileStore extends ProfileStore {

    /**
     * Persist a batch of profile mutations.
     *
     * <p>If applying any mutation fails the implementation SHOULD throw
     * so the flusher can re-queue the entire batch for the next cycle.
     *
     * @param mutations ordered list of mutations to apply (never {@code null};
     *                  may be empty)
     * @throws NullPointerException if {@code mutations} is {@code null}
     * @throws RuntimeException     if the batch cannot be committed
     */
    void storeBatch(List<ProfileMutation> mutations);

    /**
     * Load every persisted row of a table in one shot for eager rehydration
     * (Contract C2: restart → rehydrate from durable store).
     *
     * <p>The default implementation returns an empty map (no eager rehydration).
     * Implementations that support table-level enumeration (e.g.
     * {@code InfinispanProfileStore}) SHOULD override this method.
     *
     * @param tableName profile table name (must not be {@code null})
     * @return map of {@code profileName → fieldMap}; empty when no rows are
     *         persisted or the implementation does not support this operation.
     *         Never {@code null}.
     */
    default Map<String, Map<String, Object>> loadTable(String tableName) {
        return Collections.emptyMap();
    }
}
