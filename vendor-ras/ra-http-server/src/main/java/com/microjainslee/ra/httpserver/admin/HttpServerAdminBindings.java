/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.httpserver.admin;

import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;

import java.util.function.BiFunction;

/**
 * Holder for the live HTTP server RA used by the admin pack, plus optional
 * app-bound HTMX panel hooks (Sync / Async / Callback) — opaque HTML only.
 */
public final class HttpServerAdminBindings {

    private static volatile HttpServerRaEndpoint endpoint;
    private static volatile HttpServerResourceAdaptor adaptor;
    private static volatile String configuredHost = "127.0.0.1";
    private static volatile int configuredPort = 8080;

    private static volatile BiFunction<String, RaAdminHttpRequest, RaAdminHttpResponse> appPanelGet;
    private static volatile BiFunction<String, RaAdminHttpRequest, RaAdminHttpResponse> appPanelPost;

    private HttpServerAdminBindings() {
    }

    public static void bind(HttpServerRaEndpoint ep) {
        endpoint = ep;
        adaptor = ep == null ? null : ep.delegate();
        if (adaptor != null) {
            configuredHost = adaptor.host();
            configuredPort = adaptor.configuredPort();
        }
    }

    public static void bind(HttpServerResourceAdaptor ra) {
        adaptor = ra;
        endpoint = null;
        if (ra != null) {
            configuredHost = ra.host();
            configuredPort = ra.configuredPort();
        }
    }

    /**
     * Bind app HTMX fragments for panels {@code sync}, {@code async}, {@code callback}.
     * Cleared only via {@link #clearAppPanels()} (not {@link #clear()}) so Apply/rebind
     * of the RA does not drop the app menu.
     */
    public static void bindAppPanels(
            BiFunction<String, RaAdminHttpRequest, RaAdminHttpResponse> get,
            BiFunction<String, RaAdminHttpRequest, RaAdminHttpResponse> post) {
        appPanelGet = get;
        appPanelPost = post;
    }

    public static void clearAppPanels() {
        appPanelGet = null;
        appPanelPost = null;
    }

    public static void clear() {
        endpoint = null;
        adaptor = null;
    }

    public static HttpServerRaEndpoint endpoint() {
        return endpoint;
    }

    public static HttpServerResourceAdaptor adaptor() {
        if (adaptor != null) {
            return adaptor;
        }
        HttpServerRaEndpoint ep = endpoint;
        return ep == null ? null : ep.delegate();
    }

    public static String configuredHost() {
        return configuredHost;
    }

    public static int configuredPort() {
        return configuredPort;
    }

    public static void setConfigured(String host, int port) {
        if (host != null && !host.isBlank()) {
            configuredHost = host;
        }
        if (port >= 0) {
            configuredPort = port;
        }
    }

    public static BiFunction<String, RaAdminHttpRequest, RaAdminHttpResponse> appPanelGet() {
        return appPanelGet;
    }

    public static BiFunction<String, RaAdminHttpRequest, RaAdminHttpResponse> appPanelPost() {
        return appPanelPost;
    }
}
