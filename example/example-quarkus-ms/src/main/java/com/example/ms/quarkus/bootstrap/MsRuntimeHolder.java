/*
 * micro-jainslee 1.2.0
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

import java.util.List;
import java.util.Map;

/**
 * Holds the booted {@link MicrosleeMsSupport.MsRuntime} for SBBs.
 */
@ApplicationScoped
public class MsRuntimeHolder {

    private volatile MicrosleeMsSupport.MsRuntime runtime;
    private volatile Map<String, List<String>> handlerBindings = Map.of();

    public void set(MicrosleeMsSupport.MsRuntime runtime) {
        set(runtime, Map.of());
    }

    public void set(MicrosleeMsSupport.MsRuntime runtime, Map<String, List<String>> handlerBindings) {
        this.runtime = runtime;
        this.handlerBindings = handlerBindings == null ? Map.of() : Map.copyOf(handlerBindings);
    }

    public MicrosleeMsSupport.MsRuntime get() {
        MicrosleeMsSupport.MsRuntime r = runtime;
        if (r == null) {
            throw new IllegalStateException("Microservice runtime not started yet");
        }
        return r;
    }

    /** n-n registry diagnostics ({@code service → binding sources}). */
    public Map<String, List<String>> handlerBindings() {
        return handlerBindings;
    }

    public boolean isReady() {
        return runtime != null;
    }
}
