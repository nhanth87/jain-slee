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
 * Application service that depends on {@code signaling}. Calls go Direct
 * (same JVM) or via Infinispan queue (cross-node).
 */
@SleeService(
        name = "app",
        transport = TransportType.INFINISPAN_QUEUE,
        dependsOn = {"signaling"},
        startPriority = 20)
public final class AppService {
}
