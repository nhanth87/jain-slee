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

import com.microjainslee.api.ProfileID;

/**
 * Hot-path interface for per-field profile CMP reads and writes.
 *
 * <p>Used by the {@code ProfileAccessorInvoker} split-package shadow to
 * read and write individual CMP fields without coupling to the concrete
 * {@link InMemoryProfileFacility} type.  This indirection allows Phase 2+
 * implementations (write-behind dirty tracking, custom stores) to intercept
 * every field mutation through a single, type-safe seam.
 *
 * <p>{@link ProfileFieldStoreLocator} resolves this interface rather than
 * the concrete class, completing the "detach locator from InMemory" goal
 * of Phase 2.
 *
 * <p>Implementations MUST be thread-safe and MUST NOT perform blocking I/O
 * (Contract C6: the hot path is called from SBB event handlers and RA
 * callbacks on Disruptor / Vert.x event-loop threads).
 *
 * @author Tran Nhan (nhanth87)
 * @see ProfileFieldStoreLocator
 */
public interface ProfileFieldAccess {

    /**
     * Read a single CMP field value for the identified profile row.
     *
     * @param id        profile identifier (must not be {@code null})
     * @param fieldName CMP field name (must not be {@code null})
     * @return the stored value, or {@code null} when the field is absent
     *         or the row does not exist
     */
    Object readField(ProfileID id, String fieldName);

    /**
     * Write a single CMP field value for the identified profile row.
     * Implementations MUST mark the row as dirty for write-behind
     * flushing when a durable store is configured.
     *
     * @param id        profile identifier (must not be {@code null})
     * @param fieldName CMP field name (must not be {@code null})
     * @param value     the value to persist; {@code null} removes the field
     * @throws IllegalStateException    when the profile table or row does not exist
     * @throws IllegalArgumentException when {@code value} violates the C7
     *                                  JDK-only type whitelist
     */
    void writeField(ProfileID id, String fieldName, Object value);
}
