/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn;

import com.microjainslee.ms.api.SleeResponse;

/**
 * Writes a sync reply for an inbox request (or no-op for fire-and-forget).
 */
@FunctionalInterface
public interface ReplyWriter {
    void write(SleeResponse response);
}
