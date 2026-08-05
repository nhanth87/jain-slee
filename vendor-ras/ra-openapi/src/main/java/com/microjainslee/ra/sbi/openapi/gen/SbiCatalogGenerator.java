/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.openapi.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks OpenAPI 3.x YAML/JSON packages and emits a deterministic {@code catalog.json}
 * compatible with {@link com.microjainslee.ra.sbi.openapi.SbiOpenApiCatalog}.
 */
public final class SbiCatalogGenerator {

    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private static final List<String> DEFAULT_REQ = List.of("application/json");
    private static final List<String> DEFAULT_RES =
            List.of("application/json", "application/problem+json");

    private static final Pattern TS_FILE = Pattern.compile("^TS\\d+_(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_IN_PATH = Pattern.compile("/(v\\d+)(?:/|$)");
    private static final Pattern BRACE_VAR = Pattern.compile("\\{[^}]+}");

    private final ObjectMapper yaml;
    private final ObjectMapper json;
    private final boolean synthesizeOptionsHead;
    private final boolean continueOnError;

    public SbiCatalogGenerator(boolean synthesizeOptionsHead, boolean continueOnError) {
        // Pin YAML + JSON to the same Jackson 2.18.x line as ra-openapi (avoid YAMLParser skew).
        this.yaml = new ObjectMapper(new YAMLFactory());
        this.json = new ObjectMapper();
        this.synthesizeOptionsHead = synthesizeOptionsHead;
        this.continueOnError = continueOnError;
    }

    public SbiCatalogGenerator() {
        this(true, false);
    }

    /**
     * @param inputDir directory tree containing {@code .yaml}/{@code .yml}/{@code .json} OpenAPI files
     * @return sorted operation descriptors (stable by apiName / operationId / method)
     */
    public List<OperationDescriptor> generate(Path inputDir) throws IOException {
        if (inputDir == null || !Files.isDirectory(inputDir)) {
            throw new IllegalArgumentException("inputDir must be an existing directory: " + inputDir);
        }
        List<Path> files = listOpenApiFiles(inputDir);
        if (files.isEmpty()) {
            throw new IllegalStateException("No OpenAPI YAML/JSON files under " + inputDir);
        }

        List<OperationDescriptor> collected = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Path file : files) {
            try {
                collected.addAll(parseFile(file));
            } catch (RuntimeException | IOException e) {
                String msg = file + ": " + e.getMessage();
                if (continueOnError) {
                    errors.add(msg);
                } else {
                    throw new IllegalStateException(
                            "Failed to parse OpenAPI file (use --continue-on-error to skip): " + msg, e);
                }
            }
        }

        if (!errors.isEmpty()) {
            System.err.println("[sbi-catalog-gen] skipped " + errors.size() + " file(s) with errors:");
            for (String e : errors) {
                System.err.println("  - " + e);
            }
        }

        List<OperationDescriptor> unique = disambiguateOperationIds(collected);
        if (synthesizeOptionsHead) {
            unique = synthesizeOptionsAndHead(unique);
        }
        return sortStable(unique);
    }

    public void writeCatalog(List<OperationDescriptor> ops, Path outputFile, String title, String specBase)
            throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("title", title != null ? title : "Digicom-ET 5GC SBI OpenAPI catalog (RA dispatch surface)");
        root.put("specBase", specBase != null ? specBase
                : "3GPP Rel-18 OpenAPI (Forge / jdegre mirror) / TS 29.500");
        ArrayNode arr = root.putArray("operations");
        for (OperationDescriptor op : ops) {
            ObjectNode n = arr.addObject();
            n.put("operationId", op.operationId());
            n.put("method", op.method());
            n.put("path", op.path());
            n.put("apiName", op.apiName());
            n.put("apiVersion", op.apiVersion());
            ArrayNode req = n.putArray("requestContentTypes");
            op.requestContentTypes().forEach(req::add);
            ArrayNode res = n.putArray("responseContentTypes");
            op.responseContentTypes().forEach(res::add);
        }
        Files.createDirectories(outputFile.getParent());
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        // Append trailing newline without reusing a stream Jackson may close.
        byte[] withNl = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, withNl, 0, bytes.length);
        withNl[bytes.length] = '\n';
        Files.write(outputFile, withNl);
    }

    public CatalogStats stats(List<OperationDescriptor> ops) {
        Set<String> apis = new LinkedHashSet<>();
        for (OperationDescriptor op : ops) {
            apis.add(op.apiName());
        }
        return new CatalogStats(ops.size(), apis.size());
    }

    public record CatalogStats(int operations, int apis) {}

    static List<Path> listOpenApiFiles(Path inputDir) throws IOException {
        try (Stream<Path> walk = Files.walk(inputDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        // Never ingest our own catalog.json output as an OpenAPI document.
                        if ("catalog.json".equals(n)) {
                            return false;
                        }
                        return n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json");
                    })
                    .sorted()
                    .toList();
        }
    }

    List<OperationDescriptor> parseFile(Path file) throws IOException {
        JsonNode root;
        try (InputStream in = Files.newInputStream(file)) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".json")) {
                root = json.readTree(in);
            } else {
                root = yaml.readTree(in);
            }
        }
        if (root == null || !root.isObject()) {
            return List.of();
        }
        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject() || paths.isEmpty()) {
            // CommonData / schema-only packages — not an error
            return List.of();
        }

        String apiName = resolveApiName(file, root);
        String apiVersion = resolveApiVersion(root);
        String pathPrefix = resolveServerPathPrefix(root);

        List<OperationDescriptor> out = new ArrayList<>();
        var pathFields = paths.fields();
        while (pathFields.hasNext()) {
            var pe = pathFields.next();
            String pathKey = pe.getKey();
            JsonNode methods = pe.getValue();
            if (methods == null || !methods.isObject()) {
                continue;
            }
            String fullPath = joinPath(pathPrefix, pathKey);
            var mfields = methods.fields();
            while (mfields.hasNext()) {
                var me = mfields.next();
                String method = me.getKey().toUpperCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                JsonNode opNode = me.getValue();
                if (opNode == null || !opNode.isObject()) {
                    continue;
                }
                String oid = opNode.hasNonNull("operationId")
                        ? opNode.get("operationId").asText()
                        : method + "_" + fullPath.replace('/', '_').replace('{', '_').replace('}', '_');
                List<String> reqTypes = extractRequestContentTypes(opNode);
                List<String> resTypes = extractResponseContentTypes(opNode);
                // Prefer first tag as apiName when title/filename is generic? Keep filename-derived.
                if (opNode.has("tags") && opNode.get("tags").isArray() && opNode.get("tags").size() > 0) {
                    // tags are resource groups, not API names — ignore for apiName
                }
                out.add(new OperationDescriptor(
                        oid, method, fullPath, apiName, apiVersion, reqTypes, resTypes));
            }
        }
        return out;
    }

    static String resolveApiName(Path file, JsonNode root) {
        String stem = stripExtension(file.getFileName().toString());
        Matcher m = TS_FILE.matcher(stem);
        if (m.matches()) {
            return m.group(1);
        }
        JsonNode info = root.get("info");
        if (info != null && info.hasNonNull("title")) {
            String title = info.get("title").asText().trim();
            // Prefer compact token if title looks like Nnrf_NFManagement
            if (title.matches("[A-Za-z0-9_./-]+") && !title.contains(" ")) {
                return title;
            }
        }
        return stem;
    }

    static String resolveApiVersion(JsonNode root) {
        String fromServer = null;
        JsonNode servers = root.get("servers");
        if (servers != null && servers.isArray() && !servers.isEmpty()) {
            JsonNode first = servers.get(0);
            if (first != null && first.hasNonNull("url")) {
                Matcher vm = VERSION_IN_PATH.matcher(first.get("url").asText());
                if (vm.find()) {
                    fromServer = vm.group(1);
                }
            }
        }
        if (fromServer != null) {
            return fromServer;
        }
        JsonNode info = root.get("info");
        if (info != null && info.hasNonNull("version")) {
            String v = info.get("version").asText().trim();
            Matcher vm = Pattern.compile("^(v?\\d+)").matcher(v);
            if (vm.find()) {
                String g = vm.group(1);
                return g.startsWith("v") || g.startsWith("V") ? g.toLowerCase(Locale.ROOT) : "v" + g;
            }
        }
        return "v1";
    }

    static String resolveServerPathPrefix(JsonNode root) {
        JsonNode servers = root.get("servers");
        if (servers == null || !servers.isArray() || servers.isEmpty()) {
            return "";
        }
        JsonNode first = servers.get(0);
        if (first == null || !first.hasNonNull("url")) {
            return "";
        }
        String url = first.get("url").asText().trim();
        // Drop scheme/host if absolute
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            int slash = url.indexOf('/', scheme + 3);
            url = slash >= 0 ? url.substring(slash) : "";
        }
        // Remove {apiRoot} and other template vars
        url = BRACE_VAR.matcher(url).replaceAll("");
        url = url.replaceAll("/{2,}", "/");
        if (url.isBlank() || "/".equals(url)) {
            return "";
        }
        if (!url.startsWith("/")) {
            url = "/" + url;
        }
        while (url.endsWith("/") && url.length() > 1) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    static String joinPath(String prefix, String pathKey) {
        String key = pathKey == null ? "/" : pathKey.trim();
        if (!key.startsWith("/")) {
            key = "/" + key;
        }
        if (prefix == null || prefix.isBlank()) {
            return key.length() > 1 && key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        }
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String full = p + key;
        if (full.length() > 1 && full.endsWith("/")) {
            full = full.substring(0, full.length() - 1);
        }
        return full;
    }

    static List<String> extractRequestContentTypes(JsonNode opNode) {
        JsonNode rb = opNode.get("requestBody");
        if (rb == null) {
            return DEFAULT_REQ;
        }
        // requestBody may be a $ref — keep default
        if (rb.has("$ref")) {
            return DEFAULT_REQ;
        }
        JsonNode content = rb.get("content");
        if (content == null || !content.isObject() || content.isEmpty()) {
            return DEFAULT_REQ;
        }
        List<String> cts = new ArrayList<>();
        content.fieldNames().forEachRemaining(cts::add);
        cts.sort(String::compareTo);
        return cts.isEmpty() ? DEFAULT_REQ : List.copyOf(cts);
    }

    static List<String> extractResponseContentTypes(JsonNode opNode) {
        JsonNode responses = opNode.get("responses");
        if (responses == null || !responses.isObject()) {
            return DEFAULT_RES;
        }
        Set<String> cts = new LinkedHashSet<>();
        var fields = responses.fields();
        while (fields.hasNext()) {
            JsonNode resp = fields.next().getValue();
            if (resp == null || !resp.isObject()) {
                continue;
            }
            if (resp.has("$ref")) {
                continue;
            }
            JsonNode content = resp.get("content");
            if (content != null && content.isObject()) {
                content.fieldNames().forEachRemaining(cts::add);
            }
        }
        if (cts.isEmpty()) {
            return DEFAULT_RES;
        }
        if (!cts.contains("application/problem+json")) {
            cts.add("application/problem+json");
        }
        List<String> sorted = new ArrayList<>(cts);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    /**
     * When the same operationId appears under different APIs/paths, qualify all members
     * as {@code apiName.operationId} so {@code SbiOpenApiCatalog} keeps every route.
     */
    static List<OperationDescriptor> disambiguateOperationIds(List<OperationDescriptor> ops) {
        Map<String, List<OperationDescriptor>> byOid = new LinkedHashMap<>();
        for (OperationDescriptor op : ops) {
            byOid.computeIfAbsent(op.operationId(), k -> new ArrayList<>()).add(op);
        }
        Set<String> colliding = new HashSet<>();
        for (Map.Entry<String, List<OperationDescriptor>> e : byOid.entrySet()) {
            if (e.getValue().size() > 1) {
                colliding.add(e.getKey());
            }
        }
        if (colliding.isEmpty()) {
            return List.copyOf(ops);
        }
        List<OperationDescriptor> out = new ArrayList<>(ops.size());
        for (OperationDescriptor op : ops) {
            if (colliding.contains(op.operationId())) {
                out.add(op.withOperationId(op.apiName() + "." + op.operationId()));
            } else {
                out.add(op);
            }
        }
        // Second pass if qualified ids still collide (rare)
        Map<String, Integer> seen = new HashMap<>();
        List<OperationDescriptor> finalOut = new ArrayList<>(out.size());
        for (OperationDescriptor op : out) {
            int n = seen.merge(op.operationId(), 1, Integer::sum);
            if (n > 1) {
                finalOut.add(op.withOperationId(op.operationId() + "#" + n));
            } else {
                finalOut.add(op);
            }
        }
        return List.copyOf(finalOut);
    }

    /**
     * Match curated catalog convention: OPTIONS on every path; HEAD for every GET.
     */
    static List<OperationDescriptor> synthesizeOptionsAndHead(List<OperationDescriptor> ops) {
        Map<PathKey, List<OperationDescriptor>> byPath = new LinkedHashMap<>();
        for (OperationDescriptor op : ops) {
            byPath.computeIfAbsent(new PathKey(op.apiName(), op.path(), op.apiVersion()), k -> new ArrayList<>())
                    .add(op);
        }
        List<OperationDescriptor> out = new ArrayList<>(ops);
        Set<String> usedIds = new HashSet<>();
        for (OperationDescriptor op : ops) {
            usedIds.add(op.operationId());
        }

        for (Map.Entry<PathKey, List<OperationDescriptor>> e : byPath.entrySet()) {
            PathKey key = e.getKey();
            List<OperationDescriptor> group = e.getValue();
            Set<String> methods = new HashSet<>();
            for (OperationDescriptor op : group) {
                methods.add(op.method());
            }

            if (!methods.contains("OPTIONS")) {
                OperationDescriptor primary = pickPrimary(group);
                String oid = optionsOperationId(primary);
                oid = ensureUnique(oid, usedIds);
                out.add(new OperationDescriptor(
                        oid, "OPTIONS", key.path(), key.apiName(), key.apiVersion(),
                        DEFAULT_REQ, DEFAULT_RES));
            }

            if (methods.contains("GET") && !methods.contains("HEAD")) {
                OperationDescriptor getOp = group.stream()
                        .filter(o -> "GET".equals(o.method()))
                        .min(Comparator.comparing(OperationDescriptor::operationId))
                        .orElseThrow();
                String oid = ensureUnique(getOp.operationId() + "Head", usedIds);
                out.add(new OperationDescriptor(
                        oid, "HEAD", key.path(), key.apiName(), key.apiVersion(),
                        DEFAULT_REQ, DEFAULT_RES));
            }
        }
        return List.copyOf(out);
    }

    private static String ensureUnique(String oid, Set<String> usedIds) {
        String candidate = oid;
        int i = 2;
        while (!usedIds.add(candidate)) {
            candidate = oid + "_" + i++;
        }
        return candidate;
    }

    static OperationDescriptor pickPrimary(List<OperationDescriptor> group) {
        return group.stream()
                .filter(o -> !"OPTIONS".equals(o.method()) && !"HEAD".equals(o.method()))
                .min(Comparator
                        .comparingInt((OperationDescriptor o) -> methodRank(o.method()))
                        .thenComparing(OperationDescriptor::operationId))
                .orElse(group.getFirst());
    }

    static int methodRank(String method) {
        return switch (method) {
            case "POST" -> 0;
            case "PUT" -> 1;
            case "PATCH" -> 2;
            case "GET" -> 3;
            case "DELETE" -> 4;
            default -> 5;
        };
    }

    static String optionsOperationId(OperationDescriptor primary) {
        String oid = primary.operationId();
        // Strip apiName. prefix for naming if present
        String bare = oid;
        int dot = oid.lastIndexOf('.');
        if (dot > 0 && oid.substring(0, dot).equals(primary.apiName())) {
            bare = oid.substring(dot + 1);
        }
        if ("GET".equals(primary.method()) && bare.startsWith("Get") && bare.length() > 3) {
            String opts = "Options" + bare.substring(3);
            return oid.equals(bare) ? opts : primary.apiName() + "." + opts;
        }
        String opts = bare + "Options";
        return oid.equals(bare) ? opts : primary.apiName() + "." + opts;
    }

    static List<OperationDescriptor> sortStable(List<OperationDescriptor> ops) {
        return ops.stream()
                .sorted(Comparator
                        .comparing(OperationDescriptor::apiName)
                        .thenComparing(OperationDescriptor::operationId)
                        .thenComparing(OperationDescriptor::method)
                        .thenComparing(OperationDescriptor::path))
                .toList();
    }

    private static String stripExtension(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private record PathKey(String apiName, String path, String apiVersion) {}
}
