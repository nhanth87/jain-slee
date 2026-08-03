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

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared Infinispan Java-serialization allow-list for {@link ClusterManager}
 * and CMP snapshot validation in {@link DistributedSbbEntityPool}.
 *
 * <p>Infinispan 15 defaults to ProtoStream; this runtime uses
 * {@link org.infinispan.commons.marshall.JavaSerializationMarshaller} and
 * only permits types whose binary name matches one of {@link #REGEXPS}.
 */
public final class MarshallingAllowList {

    public static final String REGEXP_MICROJAINSLEE = "com\\.microjainslee\\..*";
    public static final String REGEXP_EXAMPLE = "com\\.example\\..*";
    public static final String REGEXP_JAVA = "java\\..*";
    /** Array binary names: {@code [B}, {@code [Ljava.lang.String;}, … */
    public static final String REGEXP_ARRAY = "\\[.*";

    public static final String[] REGEXPS = {
            REGEXP_MICROJAINSLEE,
            REGEXP_EXAMPLE,
            REGEXP_JAVA,
            REGEXP_ARRAY
    };

    private static final Pattern[] COMPILED;

    static {
        COMPILED = new Pattern[REGEXPS.length];
        for (int i = 0; i < REGEXPS.length; i++) {
            COMPILED[i] = Pattern.compile(REGEXPS[i]);
        }
    }

    private MarshallingAllowList() {
    }

    /** @return {@code true} when {@code type} may appear in a clustered cache value. */
    public static boolean isAllowedClass(Class<?> type) {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            return true;
        }
        if (type.isArray()) {
            return isAllowedClass(type.getComponentType());
        }
        String name = type.getName();
        for (Pattern p : COMPILED) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ensure {@code value} (and nested Collection/Map/array elements) can be
     * Java-serialized under the cluster allow-list.
     *
     * @param label field path for error messages (e.g. {@code @CmpField balance})
     * @throws IllegalArgumentException when the value is not marshallable
     */
    public static void assertMarshallable(String label, Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof Serializable)) {
            throw new IllegalArgumentException(
                    label + " is not java.io.Serializable: " + value.getClass().getName());
        }
        Class<?> type = value.getClass();
        if (!isAllowedClass(type)) {
            throw new IllegalArgumentException(
                    label + " type is outside the Infinispan serialization allow-list: "
                            + type.getName()
                            + " (allowed: com.microjainslee.*, com.example.*, java.*, arrays)");
        }
        if (value instanceof Map<?, ?> map) {
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                assertMarshallable(label + ".key[" + i + "]", e.getKey());
                assertMarshallable(label + ".value[" + i + "]", e.getValue());
                i++;
            }
        } else if (value instanceof Iterable<?> it) {
            int i = 0;
            for (Object o : it) {
                assertMarshallable(label + "[" + i + "]", o);
                i++;
            }
        } else if (type.isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                assertMarshallable(label + "[" + i + "]", Array.get(value, i));
            }
        }
    }
}
