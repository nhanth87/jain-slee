/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileAccessorBridge;
import com.microjainslee.api.ProfileFieldTypes;
import com.microjainslee.api.ProfileID;

import java.lang.reflect.Method;

/**
 * The one real {@link ProfileAccessorBridge} implementation (ADR 0004).
 * Body moved verbatim from the former split-package duplicate
 * {@code com.microjainslee.api.ProfileAccessorInvoker} that used to live in
 * this module; the api-side facade now delegates here via ServiceLoader or
 * explicit {@link com.microjainslee.api.ProfileAccessorInvoker#install}.
 *
 * <p>Reads/writes use the {@link ProfileFieldAccess} hot-path interface
 * resolved from {@link ProfileFieldStoreLocator}.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class CoreProfileAccessorBridge implements ProfileAccessorBridge {

    /**
     * Read a CMP field value via its getter accessor.
     */
    @Override
    public Object getValue(Profile profile, Method getter) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (getter == null) {
            throw new IllegalArgumentException("getter method is required");
        }
        String fieldName = com.microjainslee.api.ProfileAccessorInvoker.fieldNameFor(getter);
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
    @Override
    public void setValue(Profile profile, Method setter, Object value) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (setter == null) {
            throw new IllegalArgumentException("setter method is required");
        }
        String fieldName = com.microjainslee.api.ProfileAccessorInvoker.fieldNameFor(setter);
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
}
