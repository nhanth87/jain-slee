/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.embedded;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the embedded (plain-Java 25, no Quarkus / no Spring)
 * variant of the USSD gateway demo.
 *
 * <p>Boots a {@link MicroSleeContainer} directly, starts the JDK's
 * built-in {@code com.sun.net.httpserver.HttpServer} on the requested
 * port (default 8080) that serves {@code /api/ussd/begin-callback},
 * and blocks until SIGTERM / Ctrl-C. All wiring happens by direct
 * method calls -- no CDI, no Spring, no Quarkus.
 *
 * <p>For the Quarkus-native variant that uses the
 * {@code com.microjainslee:adapter-quarkus} CDI extension, see
 * {@code example-quarkus/} instead.
 *
 * <p>Run with:
 * <pre>
 *   mvn -B -ntp package
 *   java -jar target/example-embedded-j25.jar [port]
 * </pre>
 */
public final class EmbeddedUssdMain {

    private static final Logger LOG = LogManager.getLogger(EmbeddedUssdMain.class);

    /** Static handle the SBBs use to reach the container without CDI. */
    private static volatile MicroSleeContainer container;
    private static volatile UssdCallbackDispatcher callbackDispatcher;
    private static volatile UssdSessionStore sessionStore;
    private static volatile MockGrpcMenuClient grpcClient;
    private static volatile UssdDemoRuntime runtime;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        // --- 1. MicroSleeContainer -------------------------------------------------
        MicroSleeConfiguration configuration = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(envInt("microjainslee.buffer-size", 2048))
                .preferVirtualThreads(envBool("microjainslee.prefer-virtual-threads", true))
                .sbbPoolMin(envInt("microjainslee.sbb-pool-min", 16))
                .sbbPoolMax(envInt("microjainslee.sbb-pool-max", 4096))
                .sbbPerVirtualThread(envBool("microjainslee.sbb-per-virtual-thread", true))
                .build();
        container = new MicroSleeContainer(configuration);
        container.start();
        LOG.info("Embedded MicroSleeContainer started (bufferSize={})", configuration.getEventRouterBufferSize());

        // --- 2. Application services ----------------------------------------------
        sessionStore = new UssdSessionStore();
        callbackDispatcher = new UssdCallbackDispatcher();
        grpcClient = new MockGrpcMenuClient(envLong("ussd.demo.grpc.latency-ms", 50L));
        runtime = new UssdDemoRuntime(container, sessionStore, callbackDispatcher);

        // --- 3. HTTP front-end (JDK HttpServer) -----------------------------------
        UssdHttpServer httpServer = new UssdHttpServer(port, runtime);
        httpServer.start();
        LOG.info("Embedded USSD gateway listening on http://127.0.0.1:{}", port);

        // --- 4. Graceful shutdown -------------------------------------------------
        // Register the shutdown hook exactly once per JVM. Multiple test
        // methods boot the embedded main sequentially -- without this guard
        // the JVM would have N shutdown hooks queued and print "Shutdown
        // hook fired" N times on exit.
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        if (shutdownHook == null) {
            shutdownHook = new Thread(() -> {
                LOG.info("Shutdown hook fired -- stopping HTTP server + SLEE container");
                try {
                    httpServer.stop(0);
                } catch (Exception e) {
                    LOG.warn("HTTP server stop failed", e);
                }
                callbackDispatcher.shutdown();
                container.stop();
                shutdownLatch.countDown();
            }, "embedded-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
        shutdownLatch.await();
    }

    /** Single, idempotent shutdown hook registered by the first main() call. */
    private static volatile Thread shutdownHook;

    // --- Static accessors used by SBBs (no CDI available) ------------------------

    public static UssdDemoRuntime runtime() {
        UssdDemoRuntime r = runtime;
        if (r == null) {
            throw new IllegalStateException("EmbeddedUssdMain not started yet");
        }
        return r;
    }

    public static MockGrpcMenuClient grpcClient() {
        MockGrpcMenuClient c = grpcClient;
        if (c == null) {
            throw new IllegalStateException("EmbeddedUssdMain not started yet");
        }
        return c;
    }

    // --- System property helpers (replaces MicroProfile Config) -----------------

    private static int envInt(String name, int dflt) {
        String v = System.getProperty(name);
        return v == null || v.isEmpty() ? dflt : Integer.parseInt(v);
    }

    private static long envLong(String name, long dflt) {
        String v = System.getProperty(name);
        return v == null || v.isEmpty() ? dflt : Long.parseLong(v);
    }

    private static boolean envBool(String name, boolean dflt) {
        String v = System.getProperty(name);
        return v == null || v.isEmpty() ? dflt : Boolean.parseBoolean(v);
    }

    private EmbeddedUssdMain() {
    }
}
