/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.api.exception;

/** Thrown when two {@code @SleeService} descriptors share the same name. */
public final class DuplicateServiceNameException extends IllegalArgumentException {

    public DuplicateServiceNameException(String message) {
        super(message);
    }
}
