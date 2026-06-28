/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §13.4 — thrown by {@code SleeEndpoint.fireEvent} when
 * the supplied {@link FireableEventType} is not in the set of event
 * types declared by this RA entity.
 */
public class FiredUnrecognizedEventException extends Exception {

    private static final long serialVersionUID = 1L;

    public FiredUnrecognizedEventException(String message) {
        super(message);
    }

    public FiredUnrecognizedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
