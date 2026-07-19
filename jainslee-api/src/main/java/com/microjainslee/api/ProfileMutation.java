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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of a single profile-store mutation emitted by the
 * write-behind flusher.
 *
 * <p>A mutation carries exactly one {@link Type}:
 * <ul>
 *   <li>{@link Type#UPSERT} — create or replace all CMP fields for the
 *       identified profile row.  The {@link #getFields()} map contains
 *       the current field snapshot (JDK-only values per C7).</li>
 *   <li>{@link Type#DELETE} — remove the profile row from the durable
 *       store.  {@link #getFields()} returns {@code null} for DELETE
 *       mutations.</li>
 * </ul>
 *
 * <p>Factory methods ({@link #upsert} / {@link #delete}) are the only
 * public construction path; the class is final and immutable.
 *
 * @author Tran Nhan (nhanth87)
 * @see DurableProfileStore#storeBatch(java.util.List)
 */
public final class ProfileMutation {

    /** Kind of mutation. */
    public enum Type {
        /** Create or replace the profile row. */
        UPSERT,
        /** Remove the profile row. */
        DELETE
    }

    private final ProfileID id;
    private final Map<String, Object> fields;
    private final Type type;

    private ProfileMutation(ProfileID id, Map<String, Object> fields, Type type) {
        this.id = Objects.requireNonNull(id, "id");
        this.fields = fields;
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Build an UPSERT mutation from a field snapshot.
     *
     * @param id     profile identifier (must not be {@code null})
     * @param fields current CMP field map; a defensive copy is taken so the
     *               caller may freely mutate the map afterwards
     * @return an immutable UPSERT mutation
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ProfileMutation upsert(ProfileID id, Map<String, Object> fields) {
        Objects.requireNonNull(fields, "fields");
        return new ProfileMutation(id,
                Collections.unmodifiableMap(new LinkedHashMap<>(fields)),
                Type.UPSERT);
    }

    /**
     * Build a DELETE mutation.
     *
     * @param id profile identifier (must not be {@code null})
     * @return an immutable DELETE mutation
     */
    public static ProfileMutation delete(ProfileID id) {
        return new ProfileMutation(id, null, Type.DELETE);
    }

    /** @return the profile identifier; never {@code null}. */
    public ProfileID getId() {
        return id;
    }

    /**
     * @return an immutable field snapshot for UPSERT mutations; {@code null}
     *         for DELETE mutations.
     */
    public Map<String, Object> getFields() {
        return fields;
    }

    /** @return the mutation type; never {@code null}. */
    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return "ProfileMutation{type=" + type + ", id=" + id + '}';
    }
}
