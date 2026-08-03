/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms;

import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.annotation.SleeService;

import java.nio.charset.StandardCharsets;

/**
 * Self-handling service: the jainslee-ms handler registry auto-binds this
 * class because it implements {@link SleeServiceHandler}.
 */
@SleeService(name = "app", dependsOn = {"signaling"}, startPriority = 20)
public final class AppService implements SleeServiceHandler {

    @Override
    public SleeResponse invoke(SleeRequest req) {
        return SleeResponse.ok(
                ("app:" + req.operation()).getBytes(StandardCharsets.UTF_8));
    }
}
