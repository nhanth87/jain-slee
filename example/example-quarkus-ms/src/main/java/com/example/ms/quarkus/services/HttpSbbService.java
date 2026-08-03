/*
 * micro-jainslee 1.2.0
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
 * Gateway MS service: HTTP SBB ingress that depends on {@code http-ra}.
 * Calls go Direct (same JVM) or via Infinispan queue (cross-process).
 */
@SleeService(
        name = "http-sbb",
        transport = TransportType.INFINISPAN_QUEUE,
        dependsOn = {"http-ra"},
        startPriority = 20)
public final class HttpSbbService {
}
