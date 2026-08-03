/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core;

import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.SleeServiceHandlerProvider;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * ServiceLoader-discovered test provider: one provider contributing the same
 * handler logic to two services (the n side of n-n).
 */
public final class RegistryTestProvider implements SleeServiceHandlerProvider {

    @Override
    public Collection<String> serviceNames() {
        return List.of("alpha", "beta");
    }

    @Override
    public SleeServiceHandler create(SleeServiceDescriptor descriptor) {
        String service = descriptor.name();
        return req -> SleeResponse.ok(
                ("prov:" + service + ":" + req.operation()).getBytes(StandardCharsets.UTF_8));
    }
}
