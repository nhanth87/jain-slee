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

import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;

/**
 * Bootstraps a {@link ResourceAdaptor} with {@link RaBootstrapContextImpl}.
 */
public final class RaBootstrap {

    private RaBootstrap() {
    }

    public static RaBootstrapContextImpl activate(MicroSleeContainer container,
                                                  Class<? extends ResourceAdaptor> raClass,
                                                  String entityName) {
        return container.bootstrapResourceAdaptor(raClass.getName(), entityName);
    }
}
