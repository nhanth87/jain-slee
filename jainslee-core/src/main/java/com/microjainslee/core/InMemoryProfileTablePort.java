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
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileTable;
import com.microjainslee.api.ProfileTablePort;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory profile table for embedded mode and unit tests.
 *
 * <p><b>Deprecated.</b> The legacy {@link ProfileTablePort} surface is being
 * superseded by the spec-aligned {@link ProfileFacility} API in
 * micro-jainslee 1.1.0. This class is retained so the existing
 * {@code findByPrimaryKey}/{@code put} pair keeps working; new code should
 * use {@code InMemoryProfileFacility} (slated for Phase 2) instead.
 */
@Deprecated
public final class InMemoryProfileTablePort implements ProfileTablePort {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, Object>>> tables =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, Object>>>();

    @Override
    public Map<String, Object> findByPrimaryKey(String tableName, String primaryKey) {
        if (tableName == null || primaryKey == null) {
            return null;
        }
        ConcurrentHashMap<String, Map<String, Object>> table = tables.get(tableName);
        if (table == null) {
            return null;
        }
        Map<String, Object> row = table.get(primaryKey);
        return row == null ? null : Collections.unmodifiableMap(row);
    }

    public void put(String tableName, String primaryKey, Map<String, Object> attributes) {
        if (tableName == null || primaryKey == null || attributes == null) {
            return;
        }
        ConcurrentHashMap<String, Map<String, Object>> table = tables.get(tableName);
        if (table == null) {
            ConcurrentHashMap<String, Map<String, Object>> created =
                    new ConcurrentHashMap<String, Map<String, Object>>();
            table = tables.putIfAbsent(tableName, created);
            if (table == null) {
                table = created;
            }
        }
        table.put(primaryKey, new ConcurrentHashMap<String, Object>(attributes));
    }

    // -----------------------------------------------------------------
    // Phase 1 stub implementations of the new ProfileFacility surface.
    // These are enough to keep the legacy class compilable; full support
    // (typed ProfileLocalObject handles, create/remove operations,
    // getProfileTable views) ships in InMemoryProfileFacility later.
    // -----------------------------------------------------------------

    @Override
    public ProfileTable getProfileTable(String tableName) {
        // Phase 1 stub: the legacy in-memory store does not yet wrap rows
        // in ProfileLocalObject handles. Return null until the full
        // InMemoryProfileFacility ships.
        return null;
    }

    @Override
    public ProfileLocalObject createProfile(String tableName, String profileName,
                                            Class<? extends Profile> profileClass) {
        throw new UnsupportedOperationException(
                "InMemoryProfileTablePort.createProfile is not supported; "
                        + "use InMemoryProfileFacility (Phase 2) instead");
    }

    @Override
    public ProfileLocalObject getProfile(ProfileID id) {
        if (id == null) {
            return null;
        }
        Map<String, Object> row = findByPrimaryKey(id.getProfileTableName(), id.getProfileName());
        if (row == null) {
            return null;
        }
        return new LegacyProfileLocalObject(id, row);
    }

    @Override
    public void removeProfile(ProfileID id) {
        if (id == null) {
            return;
        }
        ConcurrentHashMap<String, Map<String, Object>> table =
                tables.get(id.getProfileTableName());
        if (table != null) {
            table.remove(id.getProfileName());
        }
    }

    @Override
    public void createProfileTable(String tableName) {
        if (tableName == null) {
            return;
        }
        tables.putIfAbsent(tableName, new ConcurrentHashMap<String, Map<String, Object>>());
    }

    @Override
    public void removeProfileTable(String tableName) {
        if (tableName == null) {
            return;
        }
        tables.remove(tableName);
    }

    @Override
    public Set<String> getProfileTableNames() {
        return Collections.unmodifiableSet(tables.keySet());
    }

    /**
     * Read-only {@link ProfileLocalObject} backed by the legacy map row.
     * The {@link #getProfile()} method materialises a {@link MapBackedProfile}
     * shim so existing {@code ProfileFacility} callers can iterate the row's
     * fields.
     */
    private static final class LegacyProfileLocalObject implements ProfileLocalObject {
        private final ProfileID id;
        private final Map<String, Object> row;

        LegacyProfileLocalObject(ProfileID id, Map<String, Object> row) {
            this.id = id;
            this.row = row;
        }

        @Override
        public Profile getProfile() {
            return new MapBackedProfile(id, row);
        }

        @Override
        public ProfileID getProfileID() {
            return id;
        }

        @Override
        public String getProfileTableName() {
            return id.getProfileTableName();
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }
    }

    /**
     * Minimal {@link Profile} shim that exposes the legacy map row via the
     * reflective {@link Profile} surface. Writes are silently dropped to
     * preserve the legacy "put-only" semantics of the underlying store.
     */
    private static final class MapBackedProfile implements Profile {
        private final ProfileID id;
        private final Map<String, Object> row;

        MapBackedProfile(ProfileID id, Map<String, Object> row) {
            this.id = id;
            this.row = row;
        }

        @Override
        public ProfileID getProfileID() {
            return id;
        }

        @Override
        public Object getCmpField(String fieldName) {
            return fieldName == null ? null : row.get(fieldName);
        }

        @Override
        public void setCmpField(String fieldName, Object value) {
            // Read-only view of the legacy row — silently drop writes.
            // Legacy callers should use InMemoryProfileTablePort.put()
            // when they need to mutate state.
        }

        @Override
        public String[] getCmpFieldNames() {
            return row.keySet().toArray(new String[0]);
        }
    }

    /** Expose the legacy row map for adapters that need it. Visible for
     *  testing only; not part of the public API surface. */
    Map<String, Map<String, Object>> snapshotTable(String tableName) {
        ConcurrentHashMap<String, Map<String, Object>> table = tables.get(tableName);
        if (table == null) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, Object>> copy = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> e : table.entrySet()) {
            copy.put(e.getKey(), new LinkedHashMap<String, Object>(e.getValue()));
        }
        return copy;
    }

    /** Visible for testing only. */
    Collection<ProfileLocalObject> snapshotProfiles(String tableName) {
        ConcurrentHashMap<String, Map<String, Object>> table = tables.get(tableName);
        if (table == null) {
            return Collections.emptyList();
        }
        java.util.List<ProfileLocalObject> result = new java.util.ArrayList<ProfileLocalObject>();
        for (Map.Entry<String, Map<String, Object>> e : table.entrySet()) {
            ProfileID id = new ProfileID(tableName, e.getKey());
            result.add(new LegacyProfileLocalObject(id, e.getValue()));
        }
        return result;
    }
}
