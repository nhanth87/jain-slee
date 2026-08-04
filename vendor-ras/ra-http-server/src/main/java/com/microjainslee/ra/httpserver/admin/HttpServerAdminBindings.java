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

import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;

/**
 * Holder for the live HTTP server RA used by the admin pack.
 */
public final class HttpServerAdminBindings {

    private static volatile HttpServerRaEndpoint endpoint;
    private static volatile HttpServerResourceAdaptor adaptor;
    private static volatile String configuredHost = "127.0.0.1";
    private static volatile int configuredPort = 8080;

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
}
