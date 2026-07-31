/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core.client;

import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceClient;
import com.microjainslee.ms.api.exception.ServiceUnavailableException;

public final class NoOpServiceClient<T> implements SleeServiceClient<T> {

    private final String serviceName;

    public NoOpServiceClient(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public SleeResponse call(SleeRequest request) {
        throw new ServiceUnavailableException(
                "Optional service '" + serviceName + "' is not available");
    }

    @Override
    public void notify(SleeRequest request) {
        // drop
    }

    @Override
    public String serviceName() {
        return serviceName;
    }
}
