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
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reflection helper that maps a profile's abstract {@code getXxx}/{@code setXxx}
 * CMP accessor onto a per-profile storage entry.
 *
 * <p><b>ADR 0004 — no more split-package shadow.</b> Historically this class
 * existed twice with one FQCN: a throwing stub here and the real body in
 * {@code jainslee-core}, relying on classpath ordering that Quarkus fast-jar
 * broke in production (the stub won, every profile write died with
 * {@code UnsupportedOperationException}). The duplicate is gone.
 *
 * <p>This class now <b>delegates</b> to the {@link ProfileAccessorBridge}
 * resolved in this order:
 * <ol>
 *   <li>explicit install via {@link #install(ProfileAccessorBridge)}
 *       (done by {@code MicroSleeContainer} at construction),</li>
 *   <li>{@link ServiceLoader} lookup of
 *       {@code META-INF/services/com.microjainslee.api.ProfileAccessorBridge}
 *       (provided once by {@code jainslee-core}).</li>
 * </ol>
 * When neither resolves, calls fail fast with an
 * {@link IllegalStateException} naming the missing piece.
 *
 * <p>Analog of {@code com.microjainslee.core.CmpAccessorInvoker} for SBBs.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ProfileAccessorInvoker {

    private static final AtomicReference<ProfileAccessorBridge> INSTALLED =
            new AtomicReference<>();
    private static volatile boolean serviceLoaderAttempted;
    private static volatile ProfileAccessorBridge serviceLoaderBridge;

    private ProfileAccessorInvoker() {
        // utility
    }

    /**
     * Install the runtime bridge. Idempotent for the same instance; a
     * different instance replaces the previous one (same last-writer-wins
     * semantics the global store locator always had).
     *
     * @param bridge the runtime implementation; must not be {@code null}
     */
    public static void install(ProfileAccessorBridge bridge) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge is required");
        }
        INSTALLED.set(bridge);
    }

    /** @return the explicitly installed bridge, or {@code null}. */
    public static ProfileAccessorBridge installed() {
        return INSTALLED.get();
    }

    /**
     * Remove an explicit install (tests, embedded isolation). The next call
     * falls back to ServiceLoader resolution.
     */
    public static void uninstall() {
        INSTALLED.set(null);
    }

    /**
     * Read a CMP field value via its getter accessor.
     *
     * @param profile the profile instance to read from
     * @param getter  abstract {@code getXxx} method declared on the profile class
     * @return the value stored for the CMP field, or the Java default for unset primitives
     * @throws IllegalStateException when no runtime bridge is present
     *         (jainslee-core missing from the runtime classpath)
     */
    public static Object getValue(Profile profile, Method getter) {
        return requireBridge().getValue(profile, getter);
    }

    /**
     * Write a CMP field value via its setter accessor.
     *
     * @param profile the profile instance to write into
     * @param setter  abstract {@code setXxx} method declared on the profile class
     * @param value   the value to persist
     * @throws IllegalStateException when no runtime bridge is present
     *         (jainslee-core missing from the runtime classpath)
     */
    public static void setValue(Profile profile, Method setter, Object value) {
        requireBridge().setValue(profile, setter, value);
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

    private static ProfileAccessorBridge requireBridge() {
        ProfileAccessorBridge direct = INSTALLED.get();
        if (direct != null) {
            return direct;
        }
        ProfileAccessorBridge viaSpi = resolveViaServiceLoader();
        if (viaSpi != null) {
            return viaSpi;
        }
        throw new IllegalStateException(
                "No ProfileAccessorBridge installed and none found via "
                        + "META-INF/services/com.microjainslee.api.ProfileAccessorBridge. "
                        + "Add jainslee-core to the runtime classpath (ADR 0004).");
    }

    /**
     * One-shot ServiceLoader resolution, cached. Re-attempted after a failed
     * lookup only when the TCCL changes is out of scope; containers should
     * call {@link #install} explicitly instead.
     */
    private static synchronized ProfileAccessorBridge resolveViaServiceLoader() {
        if (serviceLoaderAttempted) {
            return serviceLoaderBridge;
        }
        serviceLoaderAttempted = true;
        try {
            serviceLoaderBridge = ServiceLoader.load(ProfileAccessorBridge.class).findFirst().orElse(null);
        } catch (Throwable t) {
            serviceLoaderBridge = null;
        }
        return serviceLoaderBridge;
    }
}
