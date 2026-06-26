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

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Reflection helper that maps an SBB's abstract {@code getXxx}/{@code setXxx}
 * accessor onto a {@link CmpFieldStore} entry.
 *
 * <p>JAIN-SLEE 1.1 §6.5.1 says CMP accessor methods follow JavaBeans naming:
 * a field {@code counter} is exposed via abstract {@code getCounter()} /
 * {@code setCounter(int)} methods on the SBB abstract class. This class
 * performs the naming unwrapping (strip "get"/"set", lower-case the first
 * letter) and then reads/writes the underlying {@code CmpFieldStore} for the
 * entity. {@link #defaultForType(Class)} returns sensible Java defaults for
 * primitive field types when no value has been stored yet — matching the
 * "CMP field default value" semantics of the spec.
 *
 * <p>The runtime reflection here replaces the APT/codegen path that would
 * normally generate concrete {@code getXxx}/{@code setXxx} bodies; it lets
 * micro-jainslee ship a working CMP story without forcing users to wire a
 * code-generation build step.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class CmpAccessorInvoker {

    private CmpAccessorInvoker() {
        // utility
    }

    /** Extract the CMP field name from a {@code getXxx}/{@code setXxx} method. */
    public static String fieldNameFor(Method accessor) {
        if (accessor == null) {
            throw new IllegalArgumentException("accessor method is required");
        }
        String name = accessor.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("set") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2
                && (accessor.getReturnType() == boolean.class || accessor.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        throw new IllegalArgumentException(
                "Not a JavaBeans accessor: " + accessor);
    }

    /** Read a CMP field value via its getter accessor. */
    public static Object getValue(Object sbbInstance, CmpFieldStore store,
                                 String entityId, Method getter) {
        if (sbbInstance == null) {
            throw new IllegalArgumentException("sbbInstance is required");
        }
        if (store == null) {
            throw new IllegalArgumentException("CMP store is required");
        }
        if (entityId == null) {
            throw new IllegalArgumentException("entityId is required");
        }
        if (getter == null) {
            throw new IllegalArgumentException("getter method is required");
        }
        String fieldName = fieldNameFor(getter);
        Map<String, Object> state = store.load(entityId);
        Object value = state.get(fieldName);
        if (value != null) {
            return value;
        }
        return defaultForType(getter.getReturnType());
    }

    /** Write a CMP field value via its setter accessor. */
    public static void setValue(Object sbbInstance, CmpFieldStore store,
                                String entityId, Method setter, Object value) {
        if (sbbInstance == null) {
            throw new IllegalArgumentException("sbbInstance is required");
        }
        if (store == null) {
            throw new IllegalArgumentException("CMP store is required");
        }
        if (entityId == null) {
            throw new IllegalArgumentException("entityId is required");
        }
        if (setter == null) {
            throw new IllegalArgumentException("setter method is required");
        }
        String fieldName = fieldNameFor(setter);
        Map<String, Object> state = new java.util.HashMap<String, Object>(store.load(entityId));
        if (value == null) {
            state.remove(fieldName);
        } else {
            state.put(fieldName, value);
        }
        store.store(entityId, state);
    }

    /**
     * Spec default for an unset CMP field of the given Java type.
     * Primitives get zero/false; everything else gets {@code null}.
     */
    public static Object defaultForType(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type == boolean.class) return Boolean.FALSE;
        if (type == byte.class) return Byte.valueOf((byte) 0);
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (type == float.class) return Float.valueOf(0f);
        if (type == double.class) return Double.valueOf(0d);
        if (type == char.class) return Character.valueOf('\u0000');
        return null;
    }
}