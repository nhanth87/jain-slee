/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http2.admin;

import com.microjainslee.ra.sbi.http2.SbiHttp2ResourceAdaptor;

import java.util.concurrent.atomic.AtomicReference;

public final class SbiHttp2AdminBindings {

    private static final AtomicReference<SbiHttp2ResourceAdaptor> RA = new AtomicReference<>();
    private static volatile String host = "127.0.0.1";
    private static volatile int port = 8082;

    private SbiHttp2AdminBindings() {}

    public static void bind(SbiHttp2ResourceAdaptor ra) {
        RA.set(ra);
        if (ra != null) {
            host = ra.host();
            port = ra.configuredPort();
        }
    }

    public static SbiHttp2ResourceAdaptor adaptor() {
        return RA.get();
    }

    public static void setConfigured(String h, int p) {
        host = h;
        port = p;
    }

    public static String configuredHost() { return host; }
    public static int configuredPort() { return port; }
}
