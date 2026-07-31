/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.services;

import com.microjainslee.ms.api.TransportType;
import com.microjainslee.ms.api.annotation.SleeService;

/**
 * Leaf telecom-style service (no dependencies). In cluster mode typically
 * pinned to {@code node-signaling}.
 */
@SleeService(
        name = "signaling",
        transport = TransportType.INFINISPAN_QUEUE,
        startPriority = 10)
public final class SignalingService {
}
