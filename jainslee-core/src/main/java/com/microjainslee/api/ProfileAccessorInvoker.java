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

import com.microjainslee.core.ActivityContextTransactionRegistry;
import com.microjainslee.core.CmpAccessorInvoker;
import com.microjainslee.core.InMemoryProfileFacility;
import com.microjainslee.core.ProfileFieldAccess;
import com.microjainslee.core.ProfileFieldStoreLocator;
import com.microjainslee.core.SbbTransactionContext;

import java.lang.reflect.Method;

/**
 * Real implementation of the reflective profile accessor bridge.
 * <p>
 * <b>Split-package shadow.</b> The {@code jainslee-api} JAR ships a stub
 * with the same fully-qualified name that throws
 * {@link UnsupportedOperationException}; this class, compiled into the
 * {@code jainslee-core} JAR, lives in the same
 * {@code com.microjainslee.api} package and therefore overrides the stub
 * on the runtime classpath whenever {@code jainslee-core} is present.
 *
 * <p>Reads/writes use the {@link ProfileFieldAccess} hot-path interface
 * resolved from {@link ProfileFieldStoreLocator}.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileAccessorInvoker {

    private ProfileAccessorInvoker() {}

    /**
     * Read a CMP field value via its getter accessor.
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
            return CmpAccessorInvoker.defaultForType(getter.getReturnType());
        }
        ProfileFieldAccess store = ProfileFieldStoreLocator.get();
        if (store == null) {
            throw new IllegalStateException(
                    "No ProfileFieldStore registered; is MicroSleeContainer running?");
        }
        Object value = store.readField(id, fieldName);
        return value != null ? value : CmpAccessorInvoker.defaultForType(getter.getReturnType());
    }

    /**
     * Write a CMP field value via its setter accessor.
     *
     * <p>Contract C7: value validated against JDK-only whitelist before storing.
     * <p>Contract C3: old value recorded in {@link SbbTransactionContext} for
     * rollback when inside an active event delivery.
     * <p>Contract C5: update event queued non-blocking via facility.
     */
    public static void setValue(Profile profile, Method setter, Object value) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (setter == null) {
            throw new IllegalArgumentException("setter method is required");
        }
        String fieldName = fieldNameFor(setter);
        // C7 — validate type before touching the store.
        ProfileFieldTypes.assertAllowed(fieldName, value);
        ProfileID id = profile.getProfileID();
        if (id == null) {
            throw new IllegalStateException(
                    "Profile is not bound; call bindProfile() before setCmpField()");
        }
        ProfileFieldAccess store = ProfileFieldStoreLocator.get();
        if (store == null) {
            throw new IllegalStateException(
                    "No ProfileFieldStore registered; is MicroSleeContainer running?");
        }
        // C3 — capture old value for transactional undo when inside an event delivery.
        SbbTransactionContext tx = ActivityContextTransactionRegistry.current();
        if (tx != null && tx.isActive()) {
            Object oldValue = store.readField(id, fieldName);
            tx.recordProfileWrite(id, fieldName, oldValue);
        }
        // writeField: C7 re-validation + index maintenance + dirty mark + C5 notification
        store.writeField(id, fieldName, value);
    }

    /**
     * Extract the CMP field name from a {@code getXxx}/{@code setXxx}/{@code isXxx} method.
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
                && (accessor.getReturnType() == boolean.class
                        || accessor.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        throw new IllegalArgumentException("Not a JavaBeans accessor: " + accessor);
    }
}
