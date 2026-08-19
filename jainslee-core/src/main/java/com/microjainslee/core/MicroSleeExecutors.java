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
 * Creates Java 25 virtual-thread executors.
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
     * Public accessor for {@code Executors.newVirtualThreadPerTaskExecutor()} when running on Java 25.
     * Called by {@link VirtualThreadSbbEntityPool}.
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
