/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.admin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads {@link RaAdminDashboardContributor}s via ServiceLoader, sorts manifests,
 * dispatches {@code /api/ra/{raName}/...}, and resolves classpath static assets
 * under {@code META-INF/resources/jainslee-admin/{raName}/}.
 */
public final class AdminDashboardRegistry {

    private static final Logger LOG = LogManager.getLogger(AdminDashboardRegistry.class);

    private final List<RaAdminManifest> manifests;
    private final Map<String, RaAdminManifest> byRaName = new ConcurrentHashMap<>();
    private final Map<String, RaAdminApiRegistrar.Handler> handlers = new ConcurrentHashMap<>();

    public AdminDashboardRegistry(Iterable<RaAdminDashboardContributor> contributors) {
        List<RaAdminManifest> list = new ArrayList<>();
        for (RaAdminDashboardContributor c : contributors) {
            RaAdminManifest m = c.manifest();
            if (m == null || m.raName() == null || m.raName().isBlank()) {
                LOG.warn("[admin-spi] skipping contributor with blank raName: {}",
                        c.getClass().getName());
                continue;
            }
            list.add(m);
            byRaName.put(m.raName(), m);
            RegistrarImpl reg = new RegistrarImpl(m.apiBase());
            c.registerApis(reg);
            handlers.putAll(reg.handlers);
            LOG.info("[admin-spi] registered RA admin pack raName={} tabId={} apis={}",
                    m.raName(), m.tabId(), reg.handlers.size());
        }
        list.sort(Comparator.comparingInt(RaAdminManifest::order)
                .thenComparing(RaAdminManifest::raName));
        this.manifests = List.copyOf(list);
    }

    /** Discover contributors from the context / this class loader. */
    public static AdminDashboardRegistry load() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = AdminDashboardRegistry.class.getClassLoader();
        }
        return load(cl);
    }

    public static AdminDashboardRegistry load(ClassLoader cl) {
        ServiceLoader<RaAdminDashboardContributor> loader =
                ServiceLoader.load(RaAdminDashboardContributor.class, cl);
        List<RaAdminDashboardContributor> found = new ArrayList<>();
        loader.forEach(found::add);
        return new AdminDashboardRegistry(found);
    }

    /** Test / programmatic: register extra contributors after ServiceLoader load. */
    public static AdminDashboardRegistry of(RaAdminDashboardContributor... contributors) {
        return new AdminDashboardRegistry(List.of(contributors));
    }

    public List<RaAdminManifest> manifests() {
        return manifests;
    }

    public Optional<RaAdminManifest> manifest(String raName) {
        return Optional.ofNullable(byRaName.get(raName));
    }

    /**
     * Dispatch an admin API call. Path must be absolute starting with
     * {@code /api/ra/}; returns empty when no handler matches.
     */
    public Optional<RaAdminHttpResponse> dispatch(RaAdminHttpRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        String path = normalize(request.path());
        if (!path.startsWith("/api/ra/")) {
            return Optional.empty();
        }
        String method = request.method().toUpperCase(Locale.ROOT);
        String key = method + " " + path;
        RaAdminApiRegistrar.Handler h = handlers.get(key);
        if (h == null) {
            // try without trailing slash
            if (path.endsWith("/") && path.length() > 1) {
                h = handlers.get(method + " " + path.substring(0, path.length() - 1));
            }
        }
        if (h == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(h.handle(request));
        } catch (RuntimeException ex) {
            LOG.warn("[admin-spi] handler error {} {}: {}", method, path, ex.getMessage());
            return Optional.of(RaAdminHttpResponse.error(500, ex.getMessage()));
        }
    }

    /**
     * Resolve a static admin asset. {@code relativePath} is after
     * {@code /admin/ra/{raName}/} (e.g. {@code panel.html}).
     */
    public Optional<byte[]> resolveStatic(String raName, String relativePath) {
        RaAdminManifest m = byRaName.get(raName);
        if (m == null) {
            return Optional.empty();
        }
        String rel = relativePath == null ? "" : relativePath;
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isEmpty() || rel.contains("..")) {
            return Optional.empty();
        }
        String resource;
        if (rel.equals("panel.html")) {
            resource = m.resolvedFragmentPath();
        } else if (rel.equals("panel.js")) {
            resource = m.resolvedScriptPath();
        } else if (rel.equals("panel.css") && m.resolvedStylePath() != null) {
            resource = m.resolvedStylePath();
        } else {
            resource = RaAdminManifest.RESOURCE_ROOT + "/" + raName + "/" + rel;
        }
        try (InputStream in = classLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(in.readAllBytes());
        } catch (Exception ex) {
            LOG.warn("[admin-spi] failed to read {}: {}", resource, ex.getMessage());
            return Optional.empty();
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : AdminDashboardRegistry.class.getClassLoader();
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            return "/" + path;
        }
        return path;
    }

    private static String normalizeSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return "";
        }
        String s = suffix.startsWith("/") ? suffix : "/" + suffix;
        if (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static final class RegistrarImpl implements RaAdminApiRegistrar {
        private final String apiBase;
        private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

        RegistrarImpl(String apiBase) {
            String base = apiBase == null ? "" : apiBase;
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            this.apiBase = base;
        }

        @Override
        public void get(String pathSuffix, Handler handler) {
            register("GET", pathSuffix, handler);
        }

        @Override
        public void post(String pathSuffix, Handler handler) {
            register("POST", pathSuffix, handler);
        }

        @Override
        public void put(String pathSuffix, Handler handler) {
            register("PUT", pathSuffix, handler);
        }

        @Override
        public void delete(String pathSuffix, Handler handler) {
            register("DELETE", pathSuffix, handler);
        }

        private void register(String method, String pathSuffix, Handler handler) {
            String full = apiBase + normalizeSuffix(pathSuffix);
            handlers.put(method + " " + full, handler);
        }
    }
}
