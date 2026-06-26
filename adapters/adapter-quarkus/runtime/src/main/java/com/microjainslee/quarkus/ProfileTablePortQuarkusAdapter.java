/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus;

import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileTable;
import com.microjainslee.api.ProfileTablePort;
import com.microjainslee.core.InMemoryProfileTablePort;

import java.util.Map;
import java.util.Set;

/**
 * Quarkus profile table adapter — delegates to {@link InMemoryProfileTablePort} by default.
 *
 * <p>A future JPA-backed implementation can replace this bean when
 * {@code quarkus-hibernate-orm} is present, mapping profile-spec tables to
 * {@code @Entity} rows and honouring CMP field bindings from the SBB descriptor.</p>
 *
 * <p><b>Deprecated.</b> The legacy {@link ProfileTablePort} surface is being
 * superseded by {@code ProfileFacility} in micro-jainslee 1.1.0.
 */
@Deprecated
public final class ProfileTablePortQuarkusAdapter implements ProfileTablePort {

    private final InMemoryProfileTablePort delegate = new InMemoryProfileTablePort();

    @Override
    public Map<String, Object> findByPrimaryKey(String tableName, String primaryKey) {
        return delegate.findByPrimaryKey(tableName, primaryKey);
    }

    @Override
    public ProfileTable getProfileTable(String tableName) {
        return delegate.getProfileTable(tableName);
    }

    @Override
    public ProfileLocalObject createProfile(String tableName, String profileName,
                                           Class<? extends Profile> profileClass) {
        return delegate.createProfile(tableName, profileName, profileClass);
    }

    @Override
    public ProfileLocalObject getProfile(ProfileID id) {
        return delegate.getProfile(id);
    }

    @Override
    public void removeProfile(ProfileID id) {
        delegate.removeProfile(id);
    }

    @Override
    public void createProfileTable(String tableName) {
        delegate.createProfileTable(tableName);
    }

    @Override
    public void removeProfileTable(String tableName) {
        delegate.removeProfileTable(tableName);
    }

    @Override
    public Set<String> getProfileTableNames() {
        return delegate.getProfileTableNames();
    }
}
