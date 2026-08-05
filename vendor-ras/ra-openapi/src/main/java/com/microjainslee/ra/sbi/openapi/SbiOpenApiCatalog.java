/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable-after-load catalog of 5GC SBI OpenAPI operations.
 * Loads {@code sbi-openapi/catalog.json} plus any {@code sbi-openapi/**}{@code .yaml}/{@code .yml}.
 */
public final class SbiOpenApiCatalog {

    private static final Logger LOG = LogManager.getLogger(SbiOpenApiCatalog.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]+)}");
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private final Map<String, SbiOperation> byOperationId = new LinkedHashMap<>();
    private final List<CompiledRoute> routes = new ArrayList<>();
    private final Map<String, Set<String>> allowByPath = new ConcurrentHashMap<>();

    public static SbiOpenApiCatalog loadDefault() {
        SbiOpenApiCatalog cat = new SbiOpenApiCatalog();
        cat.loadFromClasspath();
        return cat;
    }

    public void loadFromClasspath() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SbiOpenApiCatalog.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream("sbi-openapi/catalog.json")) {
            if (in != null) {
                loadCatalogJson(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load sbi-openapi/catalog.json", e);
        }
        try {
            Enumeration<URL> urls = cl.getResources("sbi-openapi");
            // Also try direct known YAML via classloader resources listing is limited;
            // load seed YAML by name + discover via catalog folder listing pattern.
            String[] knownYaml = {
                    "sbi-openapi/Nnrf_NFManagement.seed.yaml"
            };
            for (String name : knownYaml) {
                try (InputStream yin = cl.getResourceAsStream(name)) {
                    if (yin != null) {
                        try {
                            loadOpenApiYaml(yin, name);
                        } catch (RuntimeException | IOException ex) {
                            LOG.warn("[ra-openapi] skip YAML {}: {}", name, ex.toString());
                        }
                    }
                }
            }
            // silence unused
            while (urls.hasMoreElements()) {
                urls.nextElement();
            }
        } catch (IOException e) {
            LOG.warn("OpenAPI YAML scan incomplete: {}", e.toString());
        }
        rebuildRoutes();
        LOG.info("[ra-openapi] catalog loaded: {} operations, {} routes, {} apis",
                byOperationId.size(), routes.size(), apiNames().size());
    }

    public void loadCatalogJson(InputStream in) throws IOException {
        JsonNode root = JSON.readTree(in);
        JsonNode ops = root.get("operations");
        if (ops == null || !ops.isArray()) {
            return;
        }
        for (JsonNode n : ops) {
            register(parseOp(n));
        }
    }

    public void loadOpenApiYaml(InputStream in, String source) throws IOException {
        JsonNode root = YAML.readTree(in);
        String apiName = source;
        JsonNode info = root.get("info");
        if (info != null && info.hasNonNull("title")) {
            apiName = info.get("title").asText();
        }
        String version = "v1";
        if (info != null && info.hasNonNull("version")) {
            version = info.get("version").asText();
        }
        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            return;
        }
        var fields = paths.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            String path = e.getKey();
            // Prefer server url prefix if relative path
            String fullPath = path.startsWith("/") ? path : "/" + path;
            JsonNode methods = e.getValue();
            var mfields = methods.fields();
            while (mfields.hasNext()) {
                var me = mfields.next();
                String method = me.getKey().toUpperCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                JsonNode opNode = me.getValue();
                String oid = opNode.hasNonNull("operationId")
                        ? opNode.get("operationId").asText()
                        : method + "_" + fullPath.replace('/', '_');
                List<String> reqTypes = List.of("application/json");
                JsonNode rb = opNode.get("requestBody");
                if (rb != null && rb.has("content") && rb.get("content").isObject()) {
                    List<String> cts = new ArrayList<>();
                    rb.get("content").fieldNames().forEachRemaining(cts::add);
                    if (!cts.isEmpty()) {
                        reqTypes = cts;
                    }
                }
                register(new SbiOperation(oid, method, fullPath, apiName, version,
                        reqTypes, List.of("application/json", "application/problem+json")));
            }
        }
    }

    public synchronized void register(SbiOperation op) {
        byOperationId.put(op.operationId(), op);
        allowByPath
                .computeIfAbsent(normalizePath(op.pathTemplate()), k -> ConcurrentHashMap.newKeySet())
                .add(op.method());
    }

    private void rebuildRoutes() {
        routes.clear();
        for (SbiOperation op : byOperationId.values()) {
            routes.add(compile(op));
        }
    }

    public int size() {
        return byOperationId.size();
    }

    public Optional<SbiOperation> byOperationId(String operationId) {
        return Optional.ofNullable(byOperationId.get(operationId));
    }

    public List<SbiOperation> all() {
        return List.copyOf(byOperationId.values());
    }

    public Set<String> apiNames() {
        Set<String> s = new LinkedHashSet<>();
        for (SbiOperation op : byOperationId.values()) {
            s.add(op.apiName());
        }
        return Collections.unmodifiableSet(s);
    }

    public Set<String> allowedMethods(String path) {
        Set<String> direct = allowByPath.get(normalizePath(path));
        if (direct != null && !direct.isEmpty()) {
            return Set.copyOf(direct);
        }
        // Template match
        Set<String> out = new LinkedHashSet<>();
        for (CompiledRoute r : routes) {
            if (r.matchesPath(normalizePath(path))) {
                out.add(r.operation.method());
            }
        }
        return out;
    }

    public Optional<SbiRouteMatch> match(String method, String path) {
        if (method == null || path == null) {
            return Optional.empty();
        }
        String m = method.toUpperCase(Locale.ROOT);
        String p = normalizePath(path);
        for (CompiledRoute r : routes) {
            if (!r.operation.method().equals(m)) {
                continue;
            }
            Map<String, String> params = r.extract(p);
            if (params != null) {
                return Optional.of(new SbiRouteMatch(r.operation, params));
            }
        }
        return Optional.empty();
    }

    private static SbiOperation parseOp(JsonNode n) {
        List<String> req = readStringList(n.get("requestContentTypes"));
        List<String> res = readStringList(n.get("responseContentTypes"));
        return new SbiOperation(
                n.get("operationId").asText(),
                n.get("method").asText(),
                n.get("path").asText(),
                n.path("apiName").asText(""),
                n.path("apiVersion").asText("v1"),
                req.isEmpty() ? null : req,
                res.isEmpty() ? null : res);
    }

    private static List<String> readStringList(JsonNode n) {
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static CompiledRoute compile(SbiOperation op) {
        String template = normalizePath(op.pathTemplate());
        Matcher matcher = PATH_PARAM.matcher(template);
        List<String> names = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            names.add(matcher.group(1));
            matcher.appendReplacement(sb, "([^/]+)");
        }
        matcher.appendTail(sb);
        Pattern pattern = Pattern.compile("^" + sb + "$");
        return new CompiledRoute(op, pattern, List.copyOf(names));
    }

    private static final class CompiledRoute {
        final SbiOperation operation;
        final Pattern pattern;
        final List<String> paramNames;

        CompiledRoute(SbiOperation operation, Pattern pattern, List<String> paramNames) {
            this.operation = operation;
            this.pattern = pattern;
            this.paramNames = paramNames;
        }

        boolean matchesPath(String path) {
            return pattern.matcher(path).matches();
        }

        Map<String, String> extract(String path) {
            Matcher m = pattern.matcher(path);
            if (!m.matches()) {
                return null;
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < paramNames.size(); i++) {
                params.put(paramNames.get(i), m.group(i + 1));
            }
            return params;
        }
    }
}
