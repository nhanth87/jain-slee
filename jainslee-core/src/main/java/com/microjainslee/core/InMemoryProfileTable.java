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

import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileFieldTypes;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * In-memory implementation of {@link ProfileTable}.
 * <p>
 * Stores profile rows in a {@link ConcurrentHashMap} keyed by profile
 * name, with a parallel map of CMP field state. The
 * {@link #put(String, Profile)} and {@link #remove(String)} methods are
 * package-private — they are intended for use by
 * {@link InMemoryProfileFacility} (and the {@code ProfileAccessorInvoker}
 * shadow) only.
 *
 * <p>CMP field reads/writes go through {@link #readField(String, String)}
 * and {@link #writeField(String, String, Object)}, which atomically
 * mutate the row's field map.
 *
 * <h3>Phase 1 additions</h3>
 * <ul>
 *   <li>Secondary index: {@link #registerIndex(String)} + maintained on
 *       every {@code writeField} and {@code remove}.</li>
 *   <li>Atomic counter ops: {@link #addToLong}, {@link #updateField},
 *       {@link #compareAndSetField} (Contract C4).</li>
 *   <li>Type-safe writes: {@code writeField} enforces
 *       {@link ProfileFieldTypes} whitelist (Contract C7).</li>
 * </ul>
 *
 * @author Tran Nhan (nhanth87)
 */
public final class InMemoryProfileTable implements ProfileTable {

    private final String tableName;
    private final ConcurrentHashMap<String, Profile> profiles =
            new ConcurrentHashMap<String, Profile>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> fields =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>();

    /**
     * Phase 1 C8 — per-row shared liveness flag.
     * {@code true} while the row exists; set to {@code false} on remove so
     * all outstanding {@link SimpleProfileLocalObject} instances for that row
     * are invalidated atomically.
     */
    private final ConcurrentHashMap<String, AtomicBoolean> rowAlive =
            new ConcurrentHashMap<String, AtomicBoolean>();

    /**
     * Phase 1 — secondary index storage.
     * Structure: indexName → (attributeValue → Set&lt;profileName&gt;)
     * The inner set uses a CHM key-set view for lock-free membership.
     * A special sentinel {@link #NULL_SENTINEL} represents a null/absent value.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Object, Set<String>>> indexes =
            new ConcurrentHashMap<String, ConcurrentHashMap<Object, Set<String>>>();

    /** Sentinel used as map key for a null attribute value in index buckets. */
    private static final Object NULL_SENTINEL = new Object() {
        @Override
        public String toString() { return "<null>"; }
    };

    public InMemoryProfileTable(String tableName) {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        this.tableName = tableName;
    }

    @Override
    public String getProfileTableName() {
        return tableName;
    }

    @Override
    public int getProfileCount() {
        return profiles.size();
    }

    @Override
    public ProfileLocalObject getProfile(String profileName) {
        if (profileName == null) {
            return null;
        }
        Profile profile = profiles.get(profileName);
        if (profile == null) {
            return null;
        }
        AtomicBoolean alive = rowAlive.getOrDefault(profileName, new AtomicBoolean(true));
        return new SimpleProfileLocalObject(profile, tableName, alive);
    }

    @Override
    public Collection<ProfileLocalObject> getProfiles() {
        Collection<ProfileLocalObject> snapshot =
                new ArrayList<ProfileLocalObject>(profiles.size());
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            AtomicBoolean alive = rowAlive.getOrDefault(entry.getKey(), new AtomicBoolean(true));
            snapshot.add(new SimpleProfileLocalObject(entry.getValue(), tableName, alive));
        }
        return Collections.unmodifiableCollection(snapshot);
    }

    @Override
    public boolean containsProfile(String profileName) {
        return profileName != null && profiles.containsKey(profileName);
    }

    // -----------------------------------------------------------------
    // Package-private mutators used by InMemoryProfileFacility and the
    // reflective ProfileAccessorInvoker.
    // -----------------------------------------------------------------

    /**
     * Atomically add a profile row. Returns {@code true} on success,
     * {@code false} if a row with the same name already exists.
     */
    boolean put(String profileName, Profile profile) {
        if (profileName == null) {
            throw new IllegalArgumentException("profileName is required");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        Profile prior = profiles.putIfAbsent(profileName, profile);
        if (prior != null) {
            return false;
        }
        fields.putIfAbsent(profileName, new ConcurrentHashMap<String, Object>());
        rowAlive.put(profileName, new AtomicBoolean(true));
        return true;
    }

    /**
     * Remove a profile row.  Cleans up secondary indexes and invalidates
     * any outstanding {@link SimpleProfileLocalObject} instances via the
     * shared liveness flag (C8).
     * Returns the removed row (or {@code null} when no such row existed).
     */
    Profile remove(String profileName) {
        if (profileName == null) {
            return null;
        }
        // C8: invalidate all LOs for this row before removing the row data.
        AtomicBoolean alive = rowAlive.remove(profileName);
        if (alive != null) {
            alive.set(false);
        }
        ConcurrentHashMap<String, Object> row = fields.remove(profileName);
        if (row != null && !indexes.isEmpty()) {
            for (Map.Entry<String, ConcurrentHashMap<Object, Set<String>>> idxEntry : indexes.entrySet()) {
                Object oldVal = row.get(idxEntry.getKey());
                indexRemove(idxEntry.getValue(), oldVal, profileName);
            }
        }
        return profiles.remove(profileName);
    }

    /**
     * Return the shared liveness flag for the given row, or {@code null}
     * when no such row exists. Used by the facility to pass the flag to
     * newly created {@link SimpleProfileLocalObject} instances.
     */
    AtomicBoolean getRowLiveness(String profileName) {
        return rowAlive.get(profileName);
    }

    // -----------------------------------------------------------------
    // Phase 1 — Secondary index management
    // -----------------------------------------------------------------

    /**
     * Register a secondary index on {@code attributeName}. Idempotent.
     * Rows already present are NOT back-filled (document: call before provisioning).
     */
    void registerIndex(String attributeName) {
        if (attributeName == null) {
            throw new IllegalArgumentException("attributeName is required");
        }
        indexes.putIfAbsent(attributeName, new ConcurrentHashMap<Object, Set<String>>());
    }

    /** @return {@code true} when an index on {@code attributeName} has been registered. */
    boolean isIndexed(String attributeName) {
        return indexes.containsKey(attributeName);
    }

    /**
     * Find all profile names whose CMP field {@code attributeName} equals
     * {@code value}.  The index must be registered first (throws if not).
     *
     * @return immutable snapshot of matching profile names (never {@code null})
     * @throws IllegalStateException when the attribute has no registered index
     */
    Set<String> findByAttribute(String attributeName, Object value) {
        ConcurrentHashMap<Object, Set<String>> idx = indexes.get(attributeName);
        if (idx == null) {
            throw new IllegalStateException(
                    "No index registered for attribute '" + attributeName
                    + "' in table '" + tableName
                    + "'. Call ProfileFacility.registerIndex() before findProfilesByAttribute()."
                    + " [§10.6 — no silent full-table scan]");
        }
        Object key = value == null ? NULL_SENTINEL : value;
        Set<String> bucket = idx.get(key);
        if (bucket == null || bucket.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<String>(bucket));
    }

    // -----------------------------------------------------------------
    // Phase 1 — Atomic field operations (C4)
    // -----------------------------------------------------------------

    /**
     * Atomically add {@code delta} to the {@code long} field {@code fieldName}
     * (treated as {@code 0L} when absent) and return the new value.
     *
     * @throws IllegalArgumentException when the row does not exist
     */
    long addToLong(String profileName, String fieldName, long delta) {
        ConcurrentHashMap<String, Object> row = requireRow(profileName);
        long[] result = new long[1];
        row.compute(fieldName, (k, current) -> {
            long cur = (current instanceof Long) ? (Long) current : 0L;
            long next = cur + delta;
            result[0] = next;
            return next;
        });
        return result[0];
    }

    /**
     * Atomically compute a new field value via {@code fn} (receives old value
     * or {@code null} when absent) and return the result.
     *
     * @throws IllegalArgumentException when the row does not exist
     * @throws IllegalArgumentException when {@code fn} returns a type not in
     *                                  {@link ProfileFieldTypes} whitelist
     */
    Object updateField(String profileName, String fieldName, UnaryOperator<Object> fn) {
        Objects.requireNonNull(fn, "fn is required");
        ConcurrentHashMap<String, Object> row = requireRow(profileName);
        Object[] result = new Object[1];
        row.compute(fieldName, (k, current) -> {
            Object next = fn.apply(current);
            ProfileFieldTypes.assertAllowed(fieldName, next);
            if (next != null) {
                // Also maintain index if registered.
                updateIndexOnWrite(profileName, fieldName, current, next);
            } else {
                updateIndexOnWrite(profileName, fieldName, current, null);
            }
            result[0] = next;
            return next; // null removes the entry from CHM
        });
        return result[0];
    }

    /**
     * Atomically compare-and-set a field value. Returns {@code true} when
     * the swap succeeded, {@code false} when the expected value did not match.
     *
     * @throws IllegalArgumentException when the row does not exist
     * @throws IllegalArgumentException when {@code update} is not in the whitelist
     */
    boolean compareAndSetField(String profileName, String fieldName,
                               Object expect, Object update) {
        ProfileFieldTypes.assertAllowed(fieldName, update);
        ConcurrentHashMap<String, Object> row = requireRow(profileName);
        boolean[] swapped = new boolean[]{false};
        row.compute(fieldName, (k, current) -> {
            boolean match = Objects.equals(current, expect);
            if (match) {
                swapped[0] = true;
                if (update != null) {
                    updateIndexOnWrite(profileName, fieldName, current, update);
                } else {
                    updateIndexOnWrite(profileName, fieldName, current, null);
                }
                return update; // null removes the CHM entry
            }
            return current;
        });
        return swapped[0];
    }

    // -----------------------------------------------------------------
    // CMP field read / write (used by ProfileAccessorInvoker shadow)
    // -----------------------------------------------------------------

    /**
     * @return an immutable snapshot of all rows + their CMP state.
     *         Visible for testing only.
     */
    Map<String, Profile> snapshotProfiles() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Profile>(profiles));
    }

    /**
     * @return an immutable snapshot of the CMP field map for the given row,
     *         or {@code null} when the row does not exist.
     */
    Map<String, Object> snapshotFields(String profileName) {
        ConcurrentHashMap<String, Object> row = fields.get(profileName);
        if (row == null) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(row));
    }

    /**
     * Read a single CMP field value for the given row. Returns {@code null}
     * when the row doesn't exist or the field hasn't been set yet.
     */
    public Object readField(String profileName, String fieldName) {
        if (profileName == null || fieldName == null) {
            return null;
        }
        ConcurrentHashMap<String, Object> row = fields.get(profileName);
        if (row == null) {
            return null;
        }
        return row.get(fieldName);
    }

    /**
     * Write a single CMP field value for the given row. Removes the entry
     * when {@code value} is {@code null}. Enforces the C7 type whitelist
     * and maintains secondary indexes on write.
     *
     * @throws IllegalArgumentException when the row does not exist or the
     *                                  value type is not in the whitelist
     */
    public void writeField(String profileName, String fieldName, Object value) {
        if (profileName == null) {
            throw new IllegalArgumentException("profileName is required");
        }
        if (fieldName == null) {
            throw new IllegalArgumentException("fieldName is required");
        }
        ProfileFieldTypes.assertAllowed(fieldName, value);
        ConcurrentHashMap<String, Object> row = fields.get(profileName);
        if (row == null) {
            throw new IllegalArgumentException(
                    "No profile row named '" + profileName + "' in table '" + tableName + "'");
        }
        Object oldValue = value == null ? row.remove(fieldName) : row.put(fieldName, value);
        updateIndexOnWrite(profileName, fieldName, oldValue, value);
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    private ConcurrentHashMap<String, Object> requireRow(String profileName) {
        ConcurrentHashMap<String, Object> row = fields.get(profileName);
        if (row == null) {
            throw new IllegalArgumentException(
                    "No profile row named '" + profileName + "' in table '" + tableName + "'");
        }
        return row;
    }

    /**
     * Update the secondary index for {@code fieldName} after a write.
     * No-op when the field has no registered index.
     */
    private void updateIndexOnWrite(String profileName, String fieldName,
                                    Object oldValue, Object newValue) {
        ConcurrentHashMap<Object, Set<String>> idx = indexes.get(fieldName);
        if (idx == null) {
            return;
        }
        indexRemove(idx, oldValue, profileName);
        if (newValue != null) {
            idx.computeIfAbsent(newValue, v -> ConcurrentHashMap.newKeySet())
               .add(profileName);
        }
    }

    /** Remove {@code profileName} from the bucket for {@code value} (handling null sentinel). */
    private static void indexRemove(ConcurrentHashMap<Object, Set<String>> idx,
                                    Object value, String profileName) {
        if (value == null) {
            value = NULL_SENTINEL;
        }
        Set<String> bucket = idx.get(value);
        if (bucket != null) {
            bucket.remove(profileName);
        }
    }
}
