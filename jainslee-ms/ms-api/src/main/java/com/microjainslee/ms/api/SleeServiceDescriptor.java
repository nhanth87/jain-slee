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

import com.microjainslee.ms.api.annotation.SleeService;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Runtime metadata for one {@link SleeService}. */
public final class SleeServiceDescriptor {

    private final String name;
    private final TransportType transport;
    private final List<String> dependsOn;
    private final List<String> optionalDeps;
    private final int startPriority;
    private final long startupTimeoutMs;
    private final Class<?> serviceClass;

    public SleeServiceDescriptor(
            String name,
            TransportType transport,
            List<String> dependsOn,
            List<String> optionalDeps,
            int startPriority,
            long startupTimeoutMs,
            Class<?> serviceClass) {
        this.name = Objects.requireNonNull(name, "name");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.dependsOn = List.copyOf(dependsOn == null ? List.of() : dependsOn);
        this.optionalDeps = List.copyOf(optionalDeps == null ? List.of() : optionalDeps);
        this.startPriority = startPriority;
        this.startupTimeoutMs = startupTimeoutMs;
        this.serviceClass = serviceClass;
    }

    public static SleeServiceDescriptor fromAnnotation(Class<?> type) {
        SleeService ann = type.getAnnotation(SleeService.class);
        if (ann == null) {
            throw new IllegalArgumentException(type.getName() + " lacks @SleeService");
        }
        return new SleeServiceDescriptor(
                ann.name(),
                ann.transport(),
                Arrays.asList(ann.dependsOn()),
                Arrays.asList(ann.optionalDeps()),
                ann.startPriority(),
                ann.startupTimeoutMs(),
                type);
    }

    public String name() {
        return name;
    }

    public TransportType transport() {
        return transport;
    }

    public List<String> dependsOn() {
        return dependsOn;
    }

    public List<String> optionalDeps() {
        return optionalDeps;
    }

    public int startPriority() {
        return startPriority;
    }

    public long startupTimeoutMs() {
        return startupTimeoutMs;
    }

    public Class<?> serviceClass() {
        return serviceClass;
    }
}
