/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.grpc;

/**
 * Application-provided upstream menu resolver (gRPC stub, test stub, etc.).
 */
public interface GrpcMenuUpstream {

    GrpcMenuUpstreamResult resolveMenu(String msisdn, String ussdString, String sessionId);

    default void close() {
    }
}
