/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra;

import com.microjainslee.api.ActivityContextInterface;

/**
 * Perfect Core S5 — minimal seam from the RA SPI back to the kernel's
 * {@code EventRouter}. Lets {@link SleeEndpointImpl} stay in
 * {@code jainslee-ra-spi} without taking a compile-time dependency on
 * {@code jainslee-core}; the kernel implements this port when it
 * builds the context through {@code ResourceAdaptorContextBuilder}.
 */
@FunctionalInterface
public interface EventRouterPort {
    void routeEvent(Object event, ActivityContextInterface aci);
}
