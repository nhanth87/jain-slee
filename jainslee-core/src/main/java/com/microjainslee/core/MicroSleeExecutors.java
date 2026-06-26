/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Creates Java 25/21 virtual-thread executors when available while keeping Java 8 bytecode.
 */
final class MicroSleeExecutors {

    private MicroSleeExecutors() {
    }

    static ExecutorService newEventExecutor(boolean preferVirtualThreads) {
        if (preferVirtualThreads) {
            ExecutorService virtualExecutor = newVirtualThreadPerTaskExecutor();
            if (virtualExecutor != null) {
                return virtualExecutor;
            }
        }
        return Executors.newCachedThreadPool();
    }

    /**
     * Public accessor for {@code Executors.newVirtualThreadPerTaskExecutor()} when running on Java 21+.
     * Returns {@code null} on Java 8/11/17 where the API does not exist. Called by
     * {@link VirtualThreadSbbEntityPool} to keep J8 source/target while still benefiting from VT on J25.
     */
    static ExecutorService newVirtualThreadPerTaskExecutor() {
        return newVirtualThreadPerTaskExecutorInternal();
    }

    private static ExecutorService newVirtualThreadPerTaskExecutorInternal() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
