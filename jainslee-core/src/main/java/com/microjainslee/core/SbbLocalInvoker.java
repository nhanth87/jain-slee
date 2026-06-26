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

import com.microjainslee.api.SbbLocalObject;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes synchronous local SBB invocations through the owning
 * {@link VirtualThreadSbbEntityPool.SbbEntity} thread.
 */
public final class SbbLocalInvoker {

    private SbbLocalInvoker() {
    }

    public static void invoke(VirtualThreadSbbEntityPool pool, SbbLocalObject target, Runnable action) {
        invoke(pool, target, new Callable<Void>() {
            @Override
            public Void call() {
                action.run();
                return null;
            }
        });
    }

    public static <T> T invoke(VirtualThreadSbbEntityPool pool, SbbLocalObject target,
            Callable<T> action) {
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        if (target.isRemoved()) {
            throw new IllegalStateException("SBB entity removed: " + target.getSbbID());
        }
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        if (pool == null) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        VirtualThreadSbbEntityPool.SbbEntity entity =
                pool.findEntity(target.getSbbID().getId());
        if (entity == null) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        entity.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(action.call());
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for local SBB invocation", e);
        }
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            if (error instanceof Error) {
                throw (Error) error;
            }
            throw new RuntimeException(error);
        }
        return result.get();
    }
}
