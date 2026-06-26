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

import com.microjainslee.core.CmpAccessorInvoker;
import com.microjainslee.core.InMemoryProfileFacility;
import com.microjainslee.core.InMemoryProfileTable;
import com.microjainslee.core.ProfileFieldStoreLocator;

import java.lang.reflect.Method;

/**
 * Real implementation of the reflective profile accessor bridge.
 * <p>
 * <b>Split-package shadow.</b> The {@code jainslee-api} JAR ships a stub
 * with the same fully-qualified name that throws
 * {@link UnsupportedOperationException}; this class, compiled into the
 * {@code jainslee-core} JAR, lives in the same
 * {@code com.microjainslee.api} package and therefore overrides the stub
 * on the runtime classpath whenever {@code jainslee-core} is present
 * (i.e. in every real deployment).
 *
 * <p>Reads resolve the bound {@link ProfileFieldStoreLocator} to obtain
 * the active {@link InMemoryProfileFacility}, walk to the
 * {@link InMemoryProfileTable} for the profile's table name, then return
 * the field value (or the Java default for unset primitives — matching
 * {@link CmpAccessorInvoker#defaultForType(Class)}).
 *
 * <p>Writes follow the symmetric path: load the row, mutate the
 * requested field, store it back. A {@code null} value removes the field.
 *
 * <p>Analog of {@link com.microjainslee.core.CmpAccessorInvoker} for SBBs.
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
     * @param getter  abstract {@code getXxx} (or {@code isXxx}) method
     *                declared on the profile class
     * @return the stored value, or the Java default for unset primitives
     * @throws IllegalArgumentException when required arguments are missing
     * @throws IllegalStateException    when no facility is bound
     */
    public static Object getValue(Profile profile, Method getter) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (getter == null) {
            throw new IllegalArgumentException("getter method is required");
        }
        String fieldName = fieldNameFor(getter);
        ProfileID id = profile.getProfileID();
        if (id == null) {
            // Profile was never bound via bindProfile(); no row to read.
            return CmpAccessorInvoker.defaultForType(getter.getReturnType());
        }
        InMemoryProfileFacility facility = ProfileFieldStoreLocator.get();
        if (facility == null) {
            throw new IllegalStateException(
                    "No ProfileFieldStore registered; is MicroSleeContainer running?");
        }
        InMemoryProfileTable table = facility.findTableInternal(id.getProfileTableName());
        if (table == null) {
            return CmpAccessorInvoker.defaultForType(getter.getReturnType());
        }
        Object value = table.readField(id.getProfileName(), fieldName);
        if (value != null) {
            return value;
        }
        return CmpAccessorInvoker.defaultForType(getter.getReturnType());
    }

    /**
     * Write a CMP field value via its setter accessor.
     *
     * @param profile the profile instance to write into
     * @param setter  abstract {@code setXxx} method declared on the profile class
     * @param value   the value to persist (may be {@code null} to clear the field)
     * @throws IllegalArgumentException when required arguments are missing
     * @throws IllegalStateException    when no facility is bound or the profile is not bound
     */
    public static void setValue(Profile profile, Method setter, Object value) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (setter == null) {
            throw new IllegalArgumentException("setter method is required");
        }
        String fieldName = fieldNameFor(setter);
        ProfileID id = profile.getProfileID();
        if (id == null) {
            throw new IllegalStateException(
                    "Profile is not bound to an identity; bindProfile() must run before setCmpField()");
        }
        InMemoryProfileFacility facility = ProfileFieldStoreLocator.get();
        if (facility == null) {
            throw new IllegalStateException(
                    "No ProfileFieldStore registered; is MicroSleeContainer running?");
        }
        InMemoryProfileTable table = facility.findTableInternal(id.getProfileTableName());
        if (table == null) {
            throw new IllegalStateException(
                    "Unrecognized profile table: " + id.getProfileTableName());
        }
        table.writeField(id.getProfileName(), fieldName, value);
    }

    /**
     * Extract the CMP field name from a {@code getXxx}/{@code setXxx} method.
     * Mirrors {@link CmpAccessorInvoker#fieldNameFor}: strips the
     * {@code get}/{@code set}/{@code is} prefix and lower-cases the next
     * character. {@code isXxx} is accepted only for {@code boolean} return
     * types.
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