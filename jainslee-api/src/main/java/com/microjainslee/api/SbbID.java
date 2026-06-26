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
 * JAIN-SLEE 1.1 §8.5 — SBB ID.
 * Unique identifier for an SBB.
 */
public final class SbbID {
    private final String id;

    public SbbID(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}