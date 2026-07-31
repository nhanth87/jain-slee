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
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.exception.ServiceCallException;

import java.util.Objects;

public final class DirectServiceClient<T> implements SleeServiceClient<T> {

    private final String serviceName;
    private final SleeServiceHandler handler;

    public DirectServiceClient(String serviceName, SleeServiceHandler handler) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public SleeResponse call(SleeRequest request) {
        try {
            return handler.invoke(request);
        } catch (Exception e) {
            throw new ServiceCallException("Direct call to '" + serviceName + "' failed", e);
        }
    }

    @Override
    public void notify(SleeRequest request) {
        call(request);
    }

    @Override
    public String serviceName() {
        return serviceName;
    }
}
