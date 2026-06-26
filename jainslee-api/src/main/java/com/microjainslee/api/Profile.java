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

/**
 * JAIN-SLEE 1.1 §10.13.1 — Profile runtime interface.
 * <p>
 * Implemented by the user-provided Profile Specification class (concretely
 * by extending {@link ProfileAbstractCmp}). Each {@code Profile} instance
 * is the in-memory snapshot of a single profile row at a given moment; the
 * underlying CMP state lives in a profile-table specific store and is
 * persisted by the container on {@code ProfileAbstractCmp}'s behalf.
 *
 * <p>micro-jainslee deliberately exposes a reflective accessor surface
 * ({@link #getCmpField(String)}, {@link #setCmpField(String, Object)},
 * {@link #getCmpFieldNames()}) on top of the typed JavaBeans accessors so
 * that callers can manipulate profile rows without a code-generated
 * concrete class.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface Profile {

    /** @return the identifier of this profile row (table + profile name). */
    ProfileID getProfileID();

    /**
     * Read a CMP field by its declared name. Returns {@code null} when no
     * value has been stored for the field yet and the field type is not a
     * primitive; primitive fields return their Java default (0, false, ...).
     *
     * @param fieldName name of the CMP field (e.g. {@code "msisdn"})
     * @return the current value, or {@code null} if unset and reference-typed
     */
    Object getCmpField(String fieldName);

    /**
     * Write a CMP field by its declared name. No-op when the field name is
     * not declared on the implementing class or when the value is of an
     * incompatible type (the spec says the SLEE is allowed to silently
     * coerce / ignore mismatched writes in a non-strict deployment).
     *
     * @param fieldName name of the CMP field
     * @param value     new value; {@code null} clears the field
     */
    void setCmpField(String fieldName, Object value);

    /**
     * @return names of all CMP fields declared via JavaBeans
     *         {@code getXxx}/{@code setXxx} pairs on the implementing class.
     */
    String[] getCmpFieldNames();
}