/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import java.util.Objects;

/**
 * Per-RA Infinispan cache names for sticky HA fabric (ADR 0002).
 *
 * <p>SS7 keeps legacy names in {@link Ss7DialogCacheNames}; new RAs use these
 * parameterized names so fabrics never collide.
 */
public final class RaActivityCacheNames {

    private RaActivityCacheNames() {
    }

    public static String owner(String raName) {
        return "ra-" + sanitize(raName) + "-owner";
    }

    public static String stickyCommands(String raName) {
        return "ra-" + sanitize(raName) + "-sticky-cmd";
    }

    public static String sessionMeta(String raName) {
        return "ra-" + sanitize(raName) + "-session-meta";
    }

    private static String sanitize(String raName) {
        Objects.requireNonNull(raName, "raName");
        String s = raName.trim().toLowerCase();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("raName blank");
        }
        return s.replaceAll("[^a-z0-9._-]", "-");
    }
}
