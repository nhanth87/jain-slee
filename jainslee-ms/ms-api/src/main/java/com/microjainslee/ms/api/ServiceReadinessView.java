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

/**
 * Readiness probe used by the orchestrator for remote dependencies.
 * Local states are always consulted first; this view covers cross-node.
 */
@FunctionalInterface
public interface ServiceReadinessView {

    ServiceState stateOf(String serviceName);
}
