/*
 * micro-jainslee example — Jakarta EE host Hello World (directory dist, no WAR)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.example.helloworld.jakartaee;

import com.example.helloworld.jakartaee.sbbs.HelloWorldSbb;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import com.microjainslee.telemetry.MicrometerTelemetryPort;
import com.microjainslee.telemetry.TelemetryDispatchObserver;
import com.microjainslee.telemetry.TelemetryRaObserver;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Embedded entrypoint for {@code dist/run.sh}. UI lives as files under
 * {@code dist/html/} (never packaged into a WAR).
 *
 * <p>For a real Jakarta EE app server, prefer {@code MicroSleeContainerStartup}
 * from adapter-jakartaee; this Main is the lab/dist host.
 */
public final class HelloWorldMain {

    private static final Logger LOG = LogManager.getLogger(HelloWorldMain.class);

    private HelloWorldMain() {
    }

    public static void main(String[] args) throws Exception {
        int httpPort = intProp("http.ra.port", 8081);
        Path htmlDir = resolveHtmlDir();

        MicroSleeContainer container = new MicroSleeContainer(MicroSleeConfiguration.defaults());
        container.start();

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerTelemetryPort telemetry = new MicrometerTelemetryPort(registry, container);
        telemetry.start();
        container.getEventRouter().setDispatchObserver(new TelemetryDispatchObserver(telemetry));
        container.setRaObserver(new TelemetryRaObserver(telemetry));

        container.registerSbbType(HelloWorldSbb.class, () -> new HelloWorldSbb(container));
        container.mapEventToSbb(HttpWebRequestEvent.class, "HelloWorldSbb");
        container.createIesDispatcher();

        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setPort(httpPort);
        HttpServerRaEndpoint httpEndpoint = new HttpServerRaEndpoint(ra);
        httpEndpoint.setPort(httpPort);
        container.registerRa(httpEndpoint, httpEndpoint);

        LOG.info("Jakarta HelloWorld dist host ready");
        LOG.info("  HTML  {}  (open index.html; or: python -m http.server -d html 8080)", htmlDir.toAbsolutePath());
        LOG.info("  RA    http://127.0.0.1:{}/hello", httpPort);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpEndpoint.deactivate();
            telemetry.stop();
            container.stop();
        }, "hello-shutdown"));

        new CountDownLatch(1).await();
    }

    private static Path resolveHtmlDir() {
        String override = System.getProperty("hello.html.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        Path cwdHtml = Path.of("html");
        if (Files.isDirectory(cwdHtml)) {
            return cwdHtml.toAbsolutePath().normalize();
        }
        return Path.of("html").toAbsolutePath().normalize();
    }

    private static int intProp(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Ignoring non-numeric {}={} — using {}", key, raw, defaultValue);
            return defaultValue;
        }
    }
}
