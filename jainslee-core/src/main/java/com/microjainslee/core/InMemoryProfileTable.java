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
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * @author Tran Nhan (nhanth87)
 */
public final class InMemoryProfileTable implements ProfileTable {

    private final String tableName;
    private final ConcurrentHashMap<String, Profile> profiles =
            new ConcurrentHashMap<String, Profile>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> fields =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>();

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
        return new SimpleProfileLocalObject(profile, tableName);
    }

    @Override
    public Collection<ProfileLocalObject> getProfiles() {
        Collection<ProfileLocalObject> snapshot =
                new ArrayList<ProfileLocalObject>(profiles.size());
        for (Profile profile : profiles.values()) {
            snapshot.add(new SimpleProfileLocalObject(profile, tableName));
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
        return true;
    }

    /**
     * Remove a profile row. Returns the removed row (or {@code null} when
     * no such row existed).
     */
    Profile remove(String profileName) {
        if (profileName == null) {
            return null;
        }
        fields.remove(profileName);
        return profiles.remove(profileName);
    }

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
     * when {@code value} is {@code null}.
     *
     * @throws IllegalArgumentException when the row does not exist
     */
    public void writeField(String profileName, String fieldName, Object value) {
        if (profileName == null) {
            throw new IllegalArgumentException("profileName is required");
        }
        if (fieldName == null) {
            throw new IllegalArgumentException("fieldName is required");
        }
        ConcurrentHashMap<String, Object> row = fields.get(profileName);
        if (row == null) {
            throw new IllegalArgumentException(
                    "No profile row named " + profileName + " in table " + tableName);
        }
        if (value == null) {
            row.remove(fieldName);
        } else {
            row.put(fieldName, value);
        }
    }
}