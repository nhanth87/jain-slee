/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.api;

import java.io.Serializable;
import java.util.Objects;

/** Cross-service response envelope. */
public final class SleeResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final byte[] payload;
    private final String errorMessage;

    private SleeResponse(boolean success, byte[] payload, String errorMessage) {
        this.success = success;
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.errorMessage = errorMessage;
    }

    public static SleeResponse ok(byte[] payload) {
        return new SleeResponse(true, payload, null);
    }

    public static SleeResponse ok() {
        return ok(new byte[0]);
    }

    public static SleeResponse error(String message) {
        return new SleeResponse(false, new byte[0], Objects.requireNonNull(message, "message"));
    }

    public boolean success() {
        return success;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public String errorMessage() {
        return errorMessage;
    }
}
