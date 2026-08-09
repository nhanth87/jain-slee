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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tunables for SBB HA checkpoint vs 10M create/delete hot path.
 *
 * <p>Read from system properties (Quarkus {@code application.properties} can
 * forward these via {@code quarkus.native…} / JVM {@code -D}):
 * <ul>
 *   <li>{@code jainslee.sbb.checkpoint.on-release} — legacy: persist on every
 *       {@code release} (default {@code false})</li>
 *   <li>{@code jainslee.sbb.checkpoint.debounce-ms} — coalesce window
 *       (default {@code 50})</li>
 * </ul>
 */
public final class SbbCheckpointConfig {

    public static final String PROP_ON_RELEASE = "jainslee.sbb.checkpoint.on-release";
    public static final String PROP_DEBOUNCE_MS = "jainslee.sbb.checkpoint.debounce-ms";

    private final boolean persistOnRelease;
    private final long debounceMs;

    public SbbCheckpointConfig(boolean persistOnRelease, long debounceMs) {
        this.persistOnRelease = persistOnRelease;
        this.debounceMs = Math.max(0L, debounceMs);
    }

    public static SbbCheckpointConfig fromSystemProperties() {
        boolean onRelease = Boolean.parseBoolean(
                System.getProperty(PROP_ON_RELEASE, "false"));
        long debounce = 50L;
        try {
            debounce = Long.parseLong(System.getProperty(PROP_DEBOUNCE_MS, "50"));
        } catch (NumberFormatException ignored) {
            debounce = 50L;
        }
        return new SbbCheckpointConfig(onRelease, debounce);
    }

    public boolean persistOnRelease() {
        return persistOnRelease;
    }

    public long debounceMs() {
        return debounceMs;
    }

    /**
     * Profile table/name refs carried in {@link SbbEntitySnapshot}
     * ({@code table/name} strings). Empty when none.
     */
    public static Set<String> copyProfileRefs(Set<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(refs));
    }

    public static String profileRef(String table, String name) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(name, "name");
        return table + "/" + name;
    }
}
