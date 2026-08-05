/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http3;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Isolates Vert.x 5.1 (HTTP/3 / QUIC) from Vert.x 4.5 used by HTTP/2.
 * Nested jars live at {@code META-INF/ra-openapi/vertx5/*.jar} inside {@code ra-openapi}.
 * Probe is best-effort — missing native transport or jar → empty Optional, never fails the RA.
 */
public final class Vertx5QuicSupport {

    private static final Logger LOG = LogManager.getLogger(Vertx5QuicSupport.class);
    private static final String EMBED_ROOT = "META-INF/ra-openapi/vertx5/";
    private static final String[] EMBEDDED = {"vertx-core-5.jar", "vertx-web-5.jar"};

    private static final AtomicReference<URLClassLoader> LOADER = new AtomicReference<>();

    private Vertx5QuicSupport() {}

    /**
     * @return child ClassLoader with Vert.x 5 only, or empty if embed missing / IO failed
     */
    public static Optional<ClassLoader> isolatedLoader() {
        URLClassLoader existing = LOADER.get();
        if (existing != null) {
            return Optional.of(existing);
        }
        synchronized (Vertx5QuicSupport.class) {
            existing = LOADER.get();
            if (existing != null) {
                return Optional.of(existing);
            }
            try {
                List<URL> urls = extractEmbeddedJars();
                if (urls.isEmpty()) {
                    LOG.info("[ra-openapi] Vert.x 5 embed absent — HTTP/3 QUIC unavailable (TCP fallback OK)");
                    return Optional.empty();
                }
                // Parent = bootstrap only — do NOT delegate io.vertx to Vert.x 4 parent
                URLClassLoader cl = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
                LOADER.set(cl);
                LOG.info("[ra-openapi] Vert.x 5 isolated ClassLoader ready ({} jars)", urls.size());
                return Optional.of(cl);
            } catch (IOException e) {
                LOG.warn("[ra-openapi] Vert.x 5 isolate failed: {}", e.toString());
                return Optional.empty();
            }
        }
    }

    /**
     * Reflective QUIC readiness probe. Returns error detail if Quic cannot start;
     * empty string means probe succeeded (server may still fail later at bind).
     */
    public static String probeQuicApi() {
        Optional<ClassLoader> cl = isolatedLoader();
        if (cl.isEmpty()) {
            return "vertx5_embed_missing";
        }
        try {
            Class.forName("io.vertx.core.http.HttpServerConfig", true, cl.get());
            Class.forName("io.vertx.core.http.HttpVersion", true, cl.get());
            return "";
        } catch (ClassNotFoundException e) {
            return "vertx5_HttpServerConfig_missing:" + e.getMessage();
        }
    }

    private static List<URL> extractEmbeddedJars() throws IOException {
        ClassLoader app = Vertx5QuicSupport.class.getClassLoader();
        Path dir = Files.createTempDirectory("ra-openapi-vertx5-");
        dir.toFile().deleteOnExit();
        List<URL> urls = new ArrayList<>(EMBEDDED.length);
        for (String name : EMBEDDED) {
            String resource = EMBED_ROOT + name;
            try (InputStream in = app.getResourceAsStream(resource)) {
                if (in == null) {
                    continue;
                }
                Path out = dir.resolve(name);
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                out.toFile().deleteOnExit();
                urls.add(out.toUri().toURL());
            }
        }
        return urls;
    }
}
