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
 * Immutable activity context handle backed by a caller-supplied id string.
 * RAs use this to fire events on a named session/dialog without depending on
 * the container implementation module.
 */
public final class SimpleActivityContextHandle implements ActivityContextHandle {

    private final String id;

    public SimpleActivityContextHandle(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("handle id is required");
        }
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SimpleActivityContextHandle)) {
            return false;
        }
        return id.equals(((SimpleActivityContextHandle) obj).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ActivityContextHandle[" + id + "]";
    }
}
