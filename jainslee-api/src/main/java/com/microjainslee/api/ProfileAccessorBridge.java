/*
 * micro-jainslee 1.2.0
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
 * Runtime bridge that executes profile CMP reads/writes against the active
 * {@link ProfileFieldAccess}-backed store.
 *
 * <p><b>ADR 0004.</b> This interface replaces the old split-package shadow:
 * {@code jainslee-api} no longer ships two competing classes with one FQCN.
 * The real implementation lives once, in {@code jainslee-core}
 * ({@code com.microjainslee.core.CoreProfileAccessorBridge}), discovered by
 * {@link java.util.ServiceLoader} (file
 * {@code META-INF/services/com.microjainslee.api.ProfileAccessorBridge}) or
 * installed programmatically via {@link ProfileAccessorInvoker#install}.
 *
 * <p>Implementations must be safe to call from any thread (SBB event handlers,
 * RA callbacks, management code) and must not cache per-thread state beyond
 * what {@code ProfileFieldStoreLocator} already provides.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface ProfileAccessorBridge {

    /**
     * Read a CMP field value via its getter accessor.
     *
     * @param profile the profile instance to read from
     * @param getter  abstract {@code getXxx}/{@code isXxx} method declared on the profile class
     * @return the stored value, or the Java default for unset primitives
     */
    Object getValue(Profile profile, Method getter);

    /**
     * Write a CMP field value via its setter accessor.
     *
     * @param profile the profile instance to write into
     * @param setter  abstract {@code setXxx} method declared on the profile class
     * @param value   the value to persist (may be {@code null})
     */
    void setValue(Profile profile, Method setter, Object value);
}
