/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core.config;

import com.microjainslee.ms.api.TransportType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable deployment topology. Prefer {@link #builder()} in tests;
 * {@link #loadYaml(String)} for the minimal deployment.yml subset.
 */
public final class DeploymentConfig {

    public enum Mode {
        SINGLE, CLUSTER
    }

    private final Mode mode;
    private final String myNodeId;
    private final Map<String, NodeConfig> nodes;
    private final Map<String, ServiceAssignment> services;

    private DeploymentConfig(
            Mode mode,
            String myNodeId,
            Map<String, NodeConfig> nodes,
            Map<String, ServiceAssignment> services) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.myNodeId = myNodeId;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        if (mode == Mode.CLUSTER) {
            if (myNodeId == null || myNodeId.isBlank()) {
                throw new IllegalArgumentException("CLUSTER mode requires myNodeId (JAINSLEE_NODE_ID)");
            }
            for (Map.Entry<String, ServiceAssignment> e : services.entrySet()) {
                if (!nodes.containsKey(e.getValue().nodeId())) {
                    throw new IllegalArgumentException(
                            "Service '" + e.getKey() + "' references unknown node '"
                                    + e.getValue().nodeId() + "'");
                }
            }
        }
    }

    public Mode mode() {
        return mode;
    }

    public String myNodeId() {
        return myNodeId;
    }

    public Map<String, NodeConfig> nodes() {
        return nodes;
    }

    public Map<String, ServiceAssignment> services() {
        return services;
    }

    public boolean isLocal(String serviceName) {
        if (mode == Mode.SINGLE) {
            return true;
        }
        ServiceAssignment sa = services.get(serviceName);
        return sa != null && myNodeId.equals(sa.nodeId());
    }

    public boolean hasService(String serviceName) {
        return mode == Mode.SINGLE || services.containsKey(serviceName);
    }

    public ServiceAssignment getService(String serviceName) {
        return services.get(serviceName);
    }

    public TransportType preferredTransport(String serviceName, TransportType fallback) {
        ServiceAssignment sa = services.get(serviceName);
        if (sa != null && sa.preferredTransport() != null) {
            return sa.preferredTransport();
        }
        return fallback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DeploymentConfig singleNode() {
        return builder().mode(Mode.SINGLE).build();
    }

    /**
     * Load from classpath resource {@code deployment.yml}, overridden by
     * env {@code JAINSLEE_DEPLOYMENT_CONFIG} (file path).
     */
    public static DeploymentConfig load() throws IOException {
        String override = System.getenv("JAINSLEE_DEPLOYMENT_CONFIG");
        String yaml;
        if (override != null && !override.isBlank()) {
            yaml = Files.readString(Path.of(override), StandardCharsets.UTF_8);
        } else {
            try (InputStream in = DeploymentConfig.class.getClassLoader()
                    .getResourceAsStream("deployment.yml")) {
                if (in == null) {
                    return singleNode();
                }
                yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        String nodeId = System.getenv("JAINSLEE_NODE_ID");
        return loadYaml(yaml, nodeId);
    }

    public static DeploymentConfig loadYaml(String yaml, String myNodeId) {
        Objects.requireNonNull(yaml, "yaml");
        Mode mode = Mode.SINGLE;
        Map<String, NodeConfig> nodes = new LinkedHashMap<>();
        Map<String, ServiceAssignment> services = new LinkedHashMap<>();
        String section = null;

        for (String raw : yaml.split("\n")) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int indent = leadingSpaces(raw.replace("\t", "    "));
            String trimmed = line.trim();

            if (indent == 0 && trimmed.endsWith(":") && !trimmed.contains(" ")) {
                section = trimmed.substring(0, trimmed.length() - 1).toLowerCase(Locale.ROOT);
                continue;
            }

            if (indent == 0 && trimmed.contains(":")) {
                String[] kv = splitKv(trimmed);
                if ("mode".equalsIgnoreCase(kv[0])) {
                    mode = Mode.valueOf(kv[1].trim().toUpperCase(Locale.ROOT));
                }
                continue;
            }

            if ("nodes".equals(section) && indent > 0) {
                // node-1: { host: "x", base-port: 9000 }  OR nested keys (unsupported) — inline map only
                String[] kv = splitKv(trimmed);
                String nodeName = kv[0].trim();
                InlineMap map = parseInlineMap(kv.length > 1 ? kv[1] : "");
                nodes.put(nodeName, new NodeConfig(
                        map.get("host", "127.0.0.1"),
                        Integer.parseInt(map.get("base-port", map.get("basePort", "9000")))));
            } else if ("services".equals(section) && indent > 0) {
                String[] kv = splitKv(trimmed);
                String svc = kv[0].trim();
                InlineMap map = parseInlineMap(kv.length > 1 ? kv[1] : "");
                String node = map.get("node", null);
                if (node == null) {
                    throw new IllegalArgumentException("Service '" + svc + "' missing node");
                }
                String transport = map.get("transport", "INFINISPAN_QUEUE");
                int port = Integer.parseInt(map.get("port", "0"));
                services.put(svc, new ServiceAssignment(
                        node,
                        TransportType.valueOf(transport.toUpperCase(Locale.ROOT)),
                        port));
            }
        }

        return new DeploymentConfig(mode, myNodeId, nodes, services);
    }

    private static String stripComment(String raw) {
        int hash = raw.indexOf('#');
        return hash >= 0 ? raw.substring(0, hash) : raw;
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String[] splitKv(String trimmed) {
        int idx = trimmed.indexOf(':');
        if (idx < 0) {
            return new String[]{trimmed, ""};
        }
        return new String[]{trimmed.substring(0, idx), trimmed.substring(idx + 1).trim()};
    }

    private static InlineMap parseInlineMap(String raw) {
        InlineMap map = new InlineMap();
        String s = raw.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isBlank()) {
            return map;
        }
        for (String part : s.split(",")) {
            String[] kv = splitKv(part.trim());
            if (kv[0].isBlank()) {
                continue;
            }
            map.put(kv[0].trim(), unquote(kv[1].trim()));
        }
        return map;
    }

    private static String unquote(String v) {
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static final class InlineMap {
        private final Map<String, String> m = new LinkedHashMap<>();

        void put(String k, String v) {
            m.put(k, v);
        }

        String get(String k, String def) {
            return m.getOrDefault(k, def);
        }
    }

    public static final class Builder {
        private Mode mode = Mode.SINGLE;
        private String myNodeId;
        private final Map<String, NodeConfig> nodes = new LinkedHashMap<>();
        private final Map<String, ServiceAssignment> services = new LinkedHashMap<>();

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder myNodeId(String myNodeId) {
            this.myNodeId = myNodeId;
            return this;
        }

        public Builder node(String id, String host, int basePort) {
            nodes.put(id, new NodeConfig(host, basePort));
            return this;
        }

        public Builder service(String name, String nodeId) {
            return service(name, nodeId, TransportType.INFINISPAN_QUEUE, 0);
        }

        public Builder service(String name, String nodeId, TransportType transport, int port) {
            services.put(name, new ServiceAssignment(nodeId, transport, port));
            return this;
        }

        public DeploymentConfig build() {
            return new DeploymentConfig(mode, myNodeId, nodes, services);
        }
    }
}
