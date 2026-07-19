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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 1 Contract C7 — CMP field value type whitelist.
 * <p>
 * Profile CMP field values must be JDK-only types to survive Quarkus
 * live-reload and classloader boundaries without {@code ClassCastException}.
 * This class defines the whitelist and provides a fast runtime check.
 *
 * <h3>Allowed types</h3>
 * <ul>
 *   <li>{@link String}</li>
 *   <li>Boxed primitives: {@link Boolean}, {@link Byte}, {@link Short},
 *       {@link Integer}, {@link Long}, {@link Float}, {@link Double},
 *       {@link Character}</li>
 *   <li>{@code byte[]} (raw binary / checkpoint payload)</li>
 *   <li>{@link List}, {@link Map}, {@link Set} — elements must also
 *       satisfy this whitelist (checked shallowly: elements are not
 *       recursively validated for performance)</li>
 * </ul>
 *
 * <h3>Forbidden types</h3>
 * <p>Application-domain enums, POJOs, and any class loaded by a non-bootstrap
 * classloader. Convert enums to {@link String} via {@code Enum.name()};
 * serialize POJOs to JSON strings before storing.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileFieldTypes {

    private ProfileFieldTypes() {
        // utility
    }

    /**
     * Return {@code true} when {@code value} is {@code null} (treated as
     * "clear the field" — always allowed) or is an instance of an allowed
     * JDK-only type.
     *
     * @param value the field value candidate (may be {@code null})
     * @return {@code true} when the value is safe to store
     */
    public static boolean isAllowed(Object value) {
        if (value == null) {
            return true;
        }
        Class<?> type = value.getClass();
        return type == String.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class
                || type == byte[].class
                || value instanceof List
                || value instanceof Map
                || value instanceof Set;
    }

    /**
     * Validate that {@code value} is an allowed type. Throws if not.
     *
     * <p>In strict mode (default: always on for Phase 1) this is called
     * from {@code ProfileAccessorInvoker.setValue} before every write.
     *
     * @param fieldName the CMP field name (for the error message)
     * @param value     the field value candidate (may be {@code null})
     * @throws IllegalArgumentException when the type is not whitelisted
     */
    public static void assertAllowed(String fieldName, Object value) {
        if (!isAllowed(value)) {
            throw new IllegalArgumentException(
                    "Profile CMP field '" + fieldName + "' rejected value of type '"
                    + value.getClass().getName()
                    + "'. Only JDK-safe types are allowed (String, boxed primitives, "
                    + "byte[], List/Map/Set). Convert enums to String (name()) and "
                    + "POJOs to JSON strings before storing in a profile field. "
                    + "[Contract C7 — classloader safety]");
        }
    }

    /**
     * Validate that a collection of values are all allowed types.
     * Shallow check: collection elements are checked but their nested
     * contents are not recursively validated.
     *
     * @param fieldName  the CMP field name (for the error message)
     * @param collection the collection to validate (may be {@code null})
     */
    public static void assertCollectionElementsAllowed(String fieldName, Collection<?> collection) {
        if (collection == null) {
            return;
        }
        for (Object element : collection) {
            assertAllowed(fieldName + "[element]", element);
        }
    }
}
