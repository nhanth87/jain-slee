/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http3.admin;

import com.microjainslee.ra.sbi.http3.SbiHttp3ResourceAdaptor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SbiHttp3AdminBindings {
    private static final AtomicReference<SbiHttp3ResourceAdaptor> RA = new AtomicReference<>();
    private static final AtomicInteger TCP = new AtomicInteger(8083);
    private static final AtomicInteger QUIC = new AtomicInteger(8443);

    private SbiHttp3AdminBindings() {}

    public static void bind(SbiHttp3ResourceAdaptor ra) {
        RA.set(ra);
        if (ra != null) {
            TCP.set(ra.tcpPort());
            QUIC.set(ra.quicPort());
        }
    }

    public static SbiHttp3ResourceAdaptor adaptor() {
        return RA.get();
    }

    public static int configuredTcpPort() {
        return TCP.get();
    }

    public static int configuredQuicPort() {
        return QUIC.get();
    }
}
