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

import com.microjainslee.api.NamingPort;

import java.util.concurrent.ConcurrentHashMap;

/**
 * CDI-friendly in-memory naming facility for embedded mode.
 */
public final class InMemoryNamingPort implements NamingPort {

    private final ConcurrentHashMap<String, Object> bindings =
            new ConcurrentHashMap<String, Object>();

    @Override
    public void bind(String name, Object value) {
        if (name == null) {
            return;
        }
        bindings.put(name, value);
    }

    @Override
    public Object lookup(String name) {
        if (name == null) {
            return null;
        }
        return bindings.get(name);
    }

    @Override
    public void unbind(String name) {
        if (name == null) {
            return;
        }
        bindings.remove(name);
    }
}
