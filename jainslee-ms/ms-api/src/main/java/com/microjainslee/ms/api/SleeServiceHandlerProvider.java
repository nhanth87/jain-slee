/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.api;

import java.util.Collection;
import java.util.List;

/**
 * SPI for automatic handler discovery ({@code META-INF/services} /
 * {@link java.util.ServiceLoader}). Bindings are n-n:
 *
 * <ul>
 *   <li>one provider may contribute handlers to <em>many</em> services
 *       ({@link #serviceNames()}), and</li>
 *   <li>one service may receive handlers from <em>many</em> providers —
 *       the runtime routes each request by {@link SleeRequest#operation()}
 *       ({@link #operations(String)}).</li>
 * </ul>
 *
 * <p>Adapter-backed services (RA needing container ports) should keep using
 * explicit {@code ServiceLifecycleHooks}; this SPI covers pure business
 * handlers.
 */
public interface SleeServiceHandlerProvider {

    /** Service names this provider contributes handlers to. */
    Collection<String> serviceNames();

    /**
     * Operations handled for {@code serviceName}. Empty means "all
     * operations" (wildcard). Operation-specific bindings win over wildcard
     * ones when routing a request.
     */
    default Collection<String> operations(String serviceName) {
        return List.of();
    }

    /** Lower values win when several bindings match the same operation. */
    default int priority() {
        return 100;
    }

    /** Create the handler for one of the declared services. */
    SleeServiceHandler create(SleeServiceDescriptor descriptor);
}
