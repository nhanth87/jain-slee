/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core;

import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;

/**
 * Adapter-bound hooks so ms-core never imports MicroSleeContainer.
 * Implementations typically call {@code registerRa}/{@code activate}/{@code deactivate}.
 */
public interface ServiceLifecycleHooks {

    /**
     * Activate a local service. Return the handler used for Direct/ISPN dispatch.
     */
    SleeServiceHandler activate(SleeServiceDescriptor descriptor) throws Exception;

    /** Deactivate a previously activated local service. */
    void deactivate(SleeServiceDescriptor descriptor) throws Exception;

    /** Publish readiness (local map and/or ISPN state cache). */
    default void publishState(String serviceName, com.microjainslee.ms.api.ServiceState state) {
        // optional
    }
}
