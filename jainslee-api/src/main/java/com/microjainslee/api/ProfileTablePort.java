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

/**
 * JAIN-SLEE 1.1 §10 — minimal Profile Table port.
 * Full profile CMP and query support is deferred to adapter-specific backends.
 */
public interface ProfileTablePort {

    /**
     * Look up a profile row by primary key.
     *
     * @param tableName   logical profile table name
     * @param primaryKey  primary key value
     * @return profile attribute map, or {@code null} when not found
     */
    java.util.Map<String, Object> findByPrimaryKey(String tableName, String primaryKey);
}
