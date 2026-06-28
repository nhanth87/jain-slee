/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.embedded;

import com.example.ussddemo.ra.GrpcMenuResourceAdaptor;
import com.example.ussddemo.ra.HttpIngressResourceAdaptor;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the embedded (plain-Java 25) USSD gateway demo.
 *
 * <p>Boots {@link MicroSleeContainer}, installs HTTP + gRPC resource adaptors,
 * and blocks until shutdown. HTTP traffic enters through
 * {@link HttpIngressResourceAdaptor} on port 8082 by default.
 *
 * <p>Run with:
 * <pre>
 *   mvn -B -ntp package
 *   java -jar target/example-embedded-j25.jar [httpPort]
 * </pre>
 *
 * <p>Start the external gRPC simulator separately:
 * <pre>
 *   cd ../grpc-simulator && mvn -B -ntp exec:java
 * </pre>
 */
public final class EmbeddedUssdMain {

    private static final Logger LOG = LogManager.getLogger(EmbeddedUssdMain.class);

    private static volatile MicroSleeContainer container;
    private static volatile EmbeddedUssdBootstrap bootstrap;
    private static volatile UssdCallbackDispatcher callbackDispatcher;
    private static volatile UssdDemoRuntime runtime;
    private static volatile Thread shutdownHook;

    public static void main(String[] args) throws Exception {
        int httpPort = args.length > 0 ? Integer.parseInt(args[0]) : defaultHttpPort();
        String grpcHost = env("ussd.demo.grpc.host", "127.0.0.1");
        int grpcPort = envInt("ussd.demo.grpc.port", 9090);

        MicroSleeConfiguration configuration = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(envInt("microjainslee.buffer-size", 2048))
                .preferVirtualThreads(envBool("microjainslee.prefer-virtual-threads", true))
                .sbbPoolMin(envInt("microjainslee.sbb-pool-min", 16))
                .sbbPoolMax(envInt("microjainslee.sbb-pool-max", 4096))
                .sbbPerVirtualThread(envBool("microjainslee.sbb-per-virtual-thread", true))
                // Perfect Core P1 — surface the tx-enabled knob so an
                // operator can flip the container into JTA mode at
                // startup. Defaults to false which keeps the example
                // self-contained.
                .txEnabled(envBool("microjainslee.tx-enabled", false))
                .build();
        container = new MicroSleeContainer(configuration);
        UssdSessionStore sessionStore = new UssdSessionStore();
        callbackDispatcher = new UssdCallbackDispatcher();
        runtime = new UssdDemoRuntime(sessionStore, callbackDispatcher);

        bootstrap = new EmbeddedUssdBootstrap(container, sessionStore);
        bootstrap.registerSbbTypesOnly();
        // Perfect Core S3 — bind the IES dispatcher before start() so
        // the event router picks up @InitialEventSelect methods on the
        // SBBs on the very first incoming event.
        bootstrap.bindInitialEventSelector();
        container.start();
        bootstrap.install(httpPort, grpcHost, grpcPort);

        int boundPort = bootstrap.httpRa().port();
        LOG.info("MicroSleeContainer started (bufferSize={})",
                configuration.getEventRouterBufferSize());
        LOG.info("Embedded USSD gateway listening on http://127.0.0.1:{}", boundPort);
        LOG.info("gRPC menu backend configured at {}:{}", grpcHost, grpcPort);

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        if (shutdownHook == null) {
            shutdownHook = new Thread(() -> {
                LOG.info("Shutdown hook fired -- stopping embedded USSD demo");
                try {
                    bootstrap.shutdown();
                } catch (Exception e) {
                    LOG.warn("Bootstrap shutdown failed", e);
                }
                callbackDispatcher.shutdown();
                container.stop();
                shutdownLatch.countDown();
            }, "embedded-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
        shutdownLatch.await();
    }

    public static MicroSleeContainer container() {
        MicroSleeContainer c = container;
        if (c == null) {
            throw new IllegalStateException("EmbeddedUssdMain not started yet");
        }
        return c;
    }

    public static EmbeddedUssdBootstrap bootstrap() {
        EmbeddedUssdBootstrap b = bootstrap;
        if (b == null) {
            throw new IllegalStateException("EmbeddedUssdMain not started yet");
        }
        return b;
    }

    public static UssdDemoRuntime runtime() {
        UssdDemoRuntime r = runtime;
        if (r == null) {
            throw new IllegalStateException("EmbeddedUssdMain not started yet");
        }
        return r;
    }

    public static GrpcMenuResourceAdaptor grpcRa() {
        return bootstrap().grpcRa();
    }

    public static HttpIngressResourceAdaptor httpRa() {
        return bootstrap().httpRa();
    }

    /** Test-only wiring when not started via {@link #main(String[])}. */
    static void bindForTest(MicroSleeContainer c,
                            EmbeddedUssdBootstrap b,
                            UssdDemoRuntime r,
                            UssdCallbackDispatcher d) {
        container = c;
        bootstrap = b;
        runtime = r;
        callbackDispatcher = d;
    }

    static void clearTestBinding() {
        container = null;
        bootstrap = null;
        runtime = null;
        callbackDispatcher = null;
    }

    private static int defaultHttpPort() {
        return envInt("ussd.demo.http.port", 8082);
    }

    private static String env(String name, String dflt) {
        String v = System.getProperty(name);
        return v == null || v.isEmpty() ? dflt : v;
    }

    private static int envInt(String name, int dflt) {
        String v = System.getProperty(name);
        return v == null || v.isEmpty() ? dflt : Integer.parseInt(v);
    }

    private static boolean envBool(String name, boolean dflt) {
        String v = System.getProperty(name);
        return v == null || v.isEmpty() ? dflt : Boolean.parseBoolean(v);
    }

    private EmbeddedUssdMain() {
    }
}
