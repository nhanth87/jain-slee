/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.jss7.admin;

import com.microjainslee.ra.jss7.Ss7RaEndpoint;
import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Holder so the admin pack can operate on the live RA without CDI.
 * Apps (e.g. OTA {@code Ss7ApplyService}) call {@link #bind} after
 * {@code registerRa}. Optional {@link #bindHooks} let the app own
 * validate/save/apply/start/stop (PG persist + plane tear-down) while
 * status still reads the live adaptor.
 *
 * <p>{@link #clear()} only drops the RA refs (stop/apply tear-down);
 * hooks survive until {@link #clearHooks()} or a new {@link #bindHooks}.
 */
public final class Ss7AdminBindings {

    private static volatile Ss7RaEndpoint endpoint;
    private static volatile Ss7ResourceAdaptor adaptor;
    private static volatile String lastConfigJson;

    private static volatile Supplier<String> applyHook;
    private static volatile Supplier<String> startHook;
    private static volatile Supplier<String> stopHook;
    private static volatile Function<String, String> validateHook;
    private static volatile Supplier<String> configJsonHook;
    private static volatile Function<String, String> saveConfigHook;

    private Ss7AdminBindings() {
    }

    public static void bind(Ss7RaEndpoint ep) {
        endpoint = ep;
        adaptor = ep == null ? null : ep.delegate();
    }

    public static void bind(Ss7ResourceAdaptor ra) {
        adaptor = ra;
        endpoint = null;
    }

    /**
     * App-owned plane control. When set, {@link Ss7AdminController} prefers
     * these over direct {@code raActive}/{@code setSs7Config}.
     */
    public static void bindHooks(Supplier<String> apply,
                                 Supplier<String> start,
                                 Supplier<String> stop,
                                 Function<String, String> validate,
                                 Supplier<String> configJson,
                                 Function<String, String> saveConfig) {
        applyHook = apply;
        startHook = start;
        stopHook = stop;
        validateHook = validate;
        configJsonHook = configJson;
        saveConfigHook = saveConfig;
    }

    /** Drop live RA refs only — used on stop/tear-down. */
    public static void clear() {
        endpoint = null;
        adaptor = null;
    }

    public static void clearHooks() {
        applyHook = null;
        startHook = null;
        stopHook = null;
        validateHook = null;
        configJsonHook = null;
        saveConfigHook = null;
    }

    public static Ss7RaEndpoint endpoint() {
        return endpoint;
    }

    public static Ss7ResourceAdaptor adaptor() {
        if (adaptor != null) {
            return adaptor;
        }
        Ss7RaEndpoint ep = endpoint;
        return ep == null ? null : ep.delegate();
    }

    public static void setLastConfigJson(String json) {
        lastConfigJson = json;
    }

    public static String lastConfigJson() {
        return lastConfigJson;
    }

    public static Supplier<String> applyHook() {
        return applyHook;
    }

    public static Supplier<String> startHook() {
        return startHook;
    }

    public static Supplier<String> stopHook() {
        return stopHook;
    }

    public static Function<String, String> validateHook() {
        return validateHook;
    }

    public static Supplier<String> configJsonHook() {
        return configJsonHook;
    }

    public static Function<String, String> saveConfigHook() {
        return saveConfigHook;
    }
}
