/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.grpc;

import com.example.ussddemo.quarkus.grpc.proto.MenuResponse;

/** Upstream menu resolver — implemented by {@link GrpcMenuClient} or test stubs. */
public interface GrpcMenuUpstream {

    MenuResponse resolveMenu(String msisdn, String ussdString, String sessionId);

    default void close() {
    }
}
