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

import com.microjainslee.api.ProfileTablePort;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory profile table for embedded mode and unit tests.
 */
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
}
