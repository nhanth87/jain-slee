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
 * JAIN-SLEE 1.1 §10 — legacy Profile Table port.
 *
 * <p><b>Deprecated.</b> This interface predates the spec-aligned
 * {@link ProfileFacility} surface introduced in micro-jainslee 1.1.0. It is
 * retained as a thin alias so existing test code that talks to
 * {@code SbbContext.getProfileFacility()} continues to compile: the
 * deprecated port {@code extends ProfileFacility} and exposes
 * {@link #findByPrimaryKey(String, String)} as a default that walks through
 * the new facility.
 *
 * <p>New code should depend on {@link ProfileFacility} directly.
 *
 * @author Tran Nhan (nhanth87)
 * @deprecated use {@link ProfileFacility} instead. Will be removed in a
 *             future major release.
 */
@Deprecated
public interface ProfileTablePort extends ProfileFacility {

    /**
     * Look up a profile row by primary key.
     *
     * <p>Default implementation delegates to {@link #getProfile(ProfileID)}
     * by composing a {@link ProfileID} from {@code tableName/primaryKey},
     * then unwrapping the underlying {@link Profile} CMP map.
     *
     * @param tableName   logical profile table name
     * @param primaryKey  primary key value
     * @return profile attribute map, or {@code null} when not found
     */
    default Map<String, Object> findByPrimaryKey(String tableName, String primaryKey) {
        if (tableName == null || primaryKey == null) {
            return null;
        }
        ProfileLocalObject plo = getProfile(new ProfileID(tableName, primaryKey));
        if (plo == null) {
            return null;
        }
        Profile profile = plo.getProfile();
        if (profile == null) {
            return null;
        }
        Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        for (String field : profile.getCmpFieldNames()) {
            result.put(field, profile.getCmpField(field));
        }
        return result;
    }
}
