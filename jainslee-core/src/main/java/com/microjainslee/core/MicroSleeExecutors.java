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

    private static ExecutorService newVirtualThreadPerTaskExecutor() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
