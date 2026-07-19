/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus;

import io.quarkus.dev.spi.HotReplacementContext;
import io.quarkus.dev.spi.HotReplacementSetup;

/**
 * Quarkus live-reload normally scans on Quarkus HTTP traffic. Apps that expose
 * only {@code ra-http-server} (and disable Quarkus HTTP) never hit that path.
 *
 * <p>Registered via {@code META-INF/services/io.quarkus.dev.spi.HotReplacementSetup}
 * on the <em>runtime</em> jar (same pattern as {@code VertxHttpHotReplacementSetup}).
 * Polls {@link HotReplacementContext#doScan(boolean)} in {@code quarkus:dev}.</p>
 */
public final class MicroJainsleeHotReplacementSetup implements HotReplacementSetup {

    private static final long INTERVAL_MS = 1000L;

    private volatile Thread ticker;

    @Override
    public void setupHotDeployment(HotReplacementContext context) {
        if (ticker != null && ticker.isAlive()) {
            return;
        }
        ticker = Thread.ofVirtual().name("microjainslee-dev-scan").start(() -> loop(context));
        System.out.println("[microjainslee] Dev live-reload scanner armed "
                + "(HotReplacementContext.doScan every " + INTERVAL_MS + "ms; "
                + "works without Quarkus HTTP)");
    }

    @Override
    public void close() {
        Thread t = ticker;
        ticker = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void loop(HotReplacementContext context) {
        try {
            Thread.sleep(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (context.doScan(false)) {
                    System.out.println("[microjainslee] live-reload scan detected changes");
                }
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
