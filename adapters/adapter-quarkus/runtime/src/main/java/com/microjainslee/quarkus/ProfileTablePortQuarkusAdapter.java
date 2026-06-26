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

import com.microjainslee.api.ProfileTablePort;
import com.microjainslee.core.InMemoryProfileTablePort;

import java.util.Map;

/**
 * Quarkus profile table adapter — delegates to {@link InMemoryProfileTablePort} by default.
 *
 * <p>A future JPA-backed implementation can replace this bean when
 * {@code quarkus-hibernate-orm} is present, mapping profile-spec tables to
 * {@code @Entity} rows and honouring CMP field bindings from the SBB descriptor.</p>
 */
public final class ProfileTablePortQuarkusAdapter implements ProfileTablePort {

    private final InMemoryProfileTablePort delegate = new InMemoryProfileTablePort();

    @Override
    public Map<String, Object> findByPrimaryKey(String tableName, String primaryKey) {
        return delegate.findByPrimaryKey(tableName, primaryKey);
    }
}
