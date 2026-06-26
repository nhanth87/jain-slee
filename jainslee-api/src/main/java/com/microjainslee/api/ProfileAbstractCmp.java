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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JAIN-SLEE 1.1 §10.13 — abstract base class for Profile Specification
 * implementations.
 * <p>
 * Analog of {@code com.microjainslee.core.CmpBackedSbb} but for profiles.
 * Subclasses declare abstract {@code getXxx}/{@code setXxx} CMP accessor
 * pairs in the usual JavaBeans style; this base class wires those accessors
 * to the container's profile store via {@link ProfileAccessorInvoker}.
 *
 * <p>Usage:
 * <pre>{@code
 * public class SubscriberProfile extends ProfileAbstractCmp {
 *     public abstract String getMsisdn();
 *     public abstract void setMsisdn(String msisdn);
 *
 *     public abstract int getBalance();
 *     public abstract void setBalance(int balance);
 * }
 * }</pre>
 *
 * <p>The container binds the profile's identity (table name + profile name)
 * via {@link #bindProfile(String, String)} immediately after instantiating
 * the class; all subsequent {@link #getProfileID()} calls return that bound
 * identity. CMP reads/writes route through {@link ProfileAccessorInvoker}
 * which delegates to a real implementation in {@code jainslee-core}.
 *
 * @author Tran Nhan (nhanth87)
 */
public abstract class ProfileAbstractCmp implements Profile {

    private String boundTableName;
    private String boundProfileName;

    /**
     * Bind the profile to its identity. Called by
     * {@code MicroSleeContainer} immediately after instantiation so all
     * subsequent accessor calls know which table row this instance
     * represents.
     *
     * @param tableName    profile table name; must not be {@code null}
     * @param profileName  profile (primary-key) name; must not be {@code null}
     */
    public final void bindProfile(String tableName, String profileName) {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (profileName == null) {
            throw new IllegalArgumentException("profileName is required");
        }
        this.boundTableName = tableName;
        this.boundProfileName = profileName;
    }

    /**
     * @return the table name this profile was bound to, or {@code null}
     *         before {@link #bindProfile(String, String)} was called.
     */
    public final String getBoundTableName() {
        return boundTableName;
    }

    /**
     * @return the profile name this profile was bound to, or {@code null}
     *         before {@link #bindProfile(String, String)} was called.
     */
    public final String getBoundProfileName() {
        return boundProfileName;
    }

    /** {@inheritDoc} */
    @Override
    public final ProfileID getProfileID() {
        if (boundTableName == null || boundProfileName == null) {
            return null;
        }
        return new ProfileID(boundTableName, boundProfileName);
    }

    /**
     * Read a CMP field value through the abstract accessor model. Subclasses
     * typically call this from their own non-abstract helper methods; the
     * {@code getXxx} declarations stay abstract per JAIN-SLEE 1.1 §10.13
     * rules so signatures match the spec.
     *
     * @param abstractGetter an abstract {@code getXxx} (or {@code isXxx}) method
     * @return the CMP value, or the Java default for unset primitives
     */
    protected final Object profileGet(Method abstractGetter) {
        return ProfileAccessorInvoker.getValue(this, abstractGetter);
    }

    /**
     * Write a CMP field value through the abstract accessor model.
     *
     * @param abstractSetter an abstract {@code setXxx} method
     * @param value          the value to persist (may be {@code null})
     */
    protected final void profileSet(Method abstractSetter, Object value) {
        ProfileAccessorInvoker.setValue(this, abstractSetter, value);
    }

    /**
     * Reflective read by field name. Looks up the {@code getXxx}/{@code isXxx}
     * method declared on this class and delegates to {@link #profileGet(Method)}.
     * Returns {@code null} when no matching accessor exists or when the field
     * is unset and reference-typed.
     */
    @Override
    public Object getCmpField(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        Method getter = findAccessor(fieldName, true);
        if (getter == null) {
            return null;
        }
        return profileGet(getter);
    }

    /**
     * Reflective write by field name. Looks up the {@code setXxx} method and
     * delegates to {@link #profileSet(Method, Object)}. No-op when the field
     * is not declared on this class or when the value is incompatible with
     * the setter's parameter type.
     */
    @Override
    public void setCmpField(String fieldName, Object value) {
        if (fieldName == null) {
            return;
        }
        Method setter = findAccessor(fieldName, false);
        if (setter == null) {
            return;
        }
        // Skip writes with a mismatched parameter type instead of throwing.
        Class<?> paramType = setter.getParameterTypes()[0];
        if (value != null && !paramType.isAssignableFrom(value.getClass())) {
            return;
        }
        profileSet(setter, value);
    }

    /**
     * Scans declared methods on the concrete class (and superclasses up to
     * this one) for JavaBeans accessor pairs and returns the union of the
     * field names. Order is declaration order, deduplicated.
     */
    @Override
    public String[] getCmpFieldNames() {
        Set<String> seen = new HashSet<String>();
        List<String> ordered = new ArrayList<String>();
        Class<?> klass = getClass();
        while (klass != null && klass != Object.class && klass != ProfileAbstractCmp.class) {
            for (Method m : klass.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                String name = m.getName();
                if (!(name.startsWith("get") || name.startsWith("set") || name.startsWith("is"))) {
                    continue;
                }
                try {
                    String fieldName = ProfileAccessorInvoker.fieldNameFor(m);
                    if (seen.add(fieldName)) {
                        ordered.add(fieldName);
                    }
                } catch (IllegalArgumentException ignored) {
                    // not a real accessor
                }
            }
            klass = klass.getSuperclass();
        }
        return ordered.toArray(new String[0]);
    }

    /**
     * Find a {@code getXxx} (or {@code isXxx} when {@code getter==true}) or
     * {@code setXxx} accessor by field name. Walks the class hierarchy up to
     * {@link ProfileAbstractCmp} (exclusive).
     *
     * @param fieldName the CMP field name
     * @param getter    {@code true} to search for a getter, {@code false} for a setter
     * @return the matching {@link Method}, or {@code null} when none exists
     */
    private Method findAccessor(String fieldName, boolean getter) {
        if (fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        Class<?> klass = getClass();
        String prefix = getter ? "get" : "set";
        while (klass != null && klass != Object.class && klass != ProfileAbstractCmp.class) {
            try {
                return klass.getDeclaredMethod(prefix + capitalized);
            } catch (NoSuchMethodException ignored) {
                // continue up the hierarchy
            }
            klass = klass.getSuperclass();
        }
        // Fall back: try isXxx for boolean getters.
        if (getter) {
            klass = getClass();
            while (klass != null && klass != Object.class && klass != ProfileAbstractCmp.class) {
                try {
                    Method m = klass.getDeclaredMethod("is" + capitalized);
                    if (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) {
                        return m;
                    }
                } catch (NoSuchMethodException ignored) {
                    // continue
                }
                klass = klass.getSuperclass();
            }
        }
        return null;
    }
}