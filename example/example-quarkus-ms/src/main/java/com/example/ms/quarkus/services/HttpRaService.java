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
 * Leaf MS service hosted with {@code ra-http-server} on {@code node-ra}.
 * In micro-services mode other nodes call it via Infinispan queue.
 */
@SleeService(
        name = "http-ra",
        transport = TransportType.INFINISPAN_QUEUE,
        startPriority = 10)
public final class HttpRaService {
}
