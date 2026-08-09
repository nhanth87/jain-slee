/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import org.jctools.maps.NonBlockingHashMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory for the SBB entity id → entity map on the create/delete hot path.
 *
 * <p>{@code -Djainslee.sbb.entity-map=jctools} (default) or {@code chm}.
 */
public final class SbbEntityMapFactory {

    public static final String PROP = "jainslee.sbb.entity-map";

    private SbbEntityMapFactory() {
    }

    public static <V> ConcurrentMap<String, V> create() {
        String mode = System.getProperty(PROP, "jctools");
        if ("chm".equalsIgnoreCase(mode) || "concurrenthashmap".equalsIgnoreCase(mode)) {
            return new ConcurrentHashMap<>();
        }
        return new NonBlockingHashMap<>();
    }
}
