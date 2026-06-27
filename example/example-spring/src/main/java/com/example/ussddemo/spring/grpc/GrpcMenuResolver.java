/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.grpc;

import com.example.ussddemo.spring.proto.MenuResponse;

@FunctionalInterface
public interface GrpcMenuResolver {

    MenuResponse resolveMenu(String msisdn, String ussdString, String sessionId);
}
