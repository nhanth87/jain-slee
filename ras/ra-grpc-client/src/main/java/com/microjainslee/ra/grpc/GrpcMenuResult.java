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
 * Immutable upstream gRPC menu resolution result.
 */
public record GrpcMenuResult(String sessionId, String status, String menuText, String error)
        implements GrpcMenuUpstreamResult {

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public String getMenuText() {
        return menuText;
    }

    @Override
    public String getError() {
        return error;
    }
}
