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

import java.lang.reflect.Method;

/**
 * Reflection helper that maps a profile's abstract {@code getXxx}/{@code setXxx}
 * CMP accessor onto a per-profile storage entry.
 * <p>
 * <b>API stub only.</b> The real implementation lives in
 * {@code com.microjainslee.core.ProfileAccessorInvoker} (in the
 * {@code jainslee-core} module) where it has access to the container-managed
 * {@code CmpFieldStore}. This stub exists so that profile code can compile
 * against the {@code jainslee-api} JAR alone and the runtime binding happens
 * at deploy time via classloader shadowing — the core module's class with the
 * same fully-qualified name wins at runtime.
 *
 * <p>Analog of {@code com.microjainslee.core.CmpAccessorInvoker} for SBBs.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileAccessorInvoker {

    private ProfileAccessorInvoker() {
        // utility
    }

    /**
     * Read a CMP field value via its getter accessor.
     *
     * @param profile the profile instance to read from
     * @param getter  abstract {@code getXxx} method declared on the profile class
     * @return the value stored for the CMP field, or the Java default for unset primitives
     * @throws UnsupportedOperationException always — the real implementation lives in jainslee-core
     */
    public static Object getValue(Profile profile, Method getter) {
        throw new UnsupportedOperationException(
                "ProfileAccessorInvoker is implemented in jainslee-core");
    }

    /**
     * Write a CMP field value via its setter accessor.
     *
     * @param profile the profile instance to write into
     * @param setter  abstract {@code setXxx} method declared on the profile class
     * @param value   the value to persist
     * @throws UnsupportedOperationException always — the real implementation lives in jainslee-core
     */
    public static void setValue(Profile profile, Method setter, Object value) {
        throw new UnsupportedOperationException(
                "ProfileAccessorInvoker is implemented in jainslee-core");
    }

    /**
     * Extract the CMP field name from a {@code getXxx}/{@code setXxx} method.
     * Mirrors {@code com.microjainslee.core.CmpAccessorInvoker.fieldNameFor}:
     * strips the {@code get}/{@code set}/{@code is} prefix and lower-cases
     * the next character. {@code isXxx} is accepted only for {@code boolean}
     * return types.
     *
     * @param accessor a {@code getXxx}, {@code setXxx}, or {@code isXxx} method
     * @return the underlying CMP field name
     * @throws IllegalArgumentException if {@code accessor} is {@code null} or not a JavaBeans accessor
     */
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
}