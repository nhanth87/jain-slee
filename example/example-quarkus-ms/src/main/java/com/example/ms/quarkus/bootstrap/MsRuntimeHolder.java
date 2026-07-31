/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.bootstrap;

import com.microjainslee.quarkus.MicrosleeMsSupport;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Holds the booted {@link MicrosleeMsSupport.MsRuntime} for REST resources.
 */
@ApplicationScoped
public class MsRuntimeHolder {

    private volatile MicrosleeMsSupport.MsRuntime runtime;

    public void set(MicrosleeMsSupport.MsRuntime runtime) {
        this.runtime = runtime;
    }

    public MicrosleeMsSupport.MsRuntime get() {
        MicrosleeMsSupport.MsRuntime r = runtime;
        if (r == null) {
            throw new IllegalStateException("Microservice runtime not started yet");
        }
        return r;
    }

    public boolean isReady() {
        return runtime != null;
    }
}
