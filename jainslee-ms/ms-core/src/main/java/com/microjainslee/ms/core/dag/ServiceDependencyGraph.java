/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core.dag;

import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.exception.CircularDependencyException;
import com.microjainslee.ms.api.exception.DuplicateServiceNameException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Kahn topological sort over {@link SleeServiceDescriptor#dependsOn()}.
 */
public final class ServiceDependencyGraph {

    private final Map<String, SleeServiceDescriptor> nodes;

    public ServiceDependencyGraph(List<SleeServiceDescriptor> descriptors) {
        Map<String, SleeServiceDescriptor> map = new HashMap<>();
        for (SleeServiceDescriptor d : descriptors) {
            Objects.requireNonNull(d, "descriptor");
            if (map.put(d.name(), d) != null) {
                throw new DuplicateServiceNameException(
                        "@SleeService name conflict: " + d.name());
            }
        }
        this.nodes = Collections.unmodifiableMap(map);
        // Fail fast on unknown hard deps
        for (SleeServiceDescriptor d : nodes.values()) {
            for (String dep : d.dependsOn()) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "Service '" + d.name() + "' dependsOn unknown '" + dep + "'");
                }
            }
        }
    }

    public SleeServiceDescriptor get(String name) {
        return nodes.get(name);
    }

    public Map<String, SleeServiceDescriptor> nodes() {
        return nodes;
    }

    public List<String> resolveStartOrder() {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (String name : nodes.keySet()) {
            inDegree.put(name, 0);
            dependents.put(name, new ArrayList<>());
        }
        for (SleeServiceDescriptor d : nodes.values()) {
            for (String dep : d.dependsOn()) {
                dependents.get(dep).add(d.name());
                inDegree.merge(d.name(), 1, Integer::sum);
            }
        }

        // Stable among equal in-degree via startPriority then name
        Queue<String> queue = new ArrayDeque<>();
        List<String> zeros = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                zeros.add(e.getKey());
            }
        }
        zeros.sort(priorityThenName());
        queue.addAll(zeros);

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            order.add(curr);
            List<String> next = new ArrayList<>(dependents.getOrDefault(curr, List.of()));
            next.sort(priorityThenName());
            for (String dependent : next) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    queue.offer(dependent);
                }
            }
        }

        if (order.size() != nodes.size()) {
            throw new CircularDependencyException(
                    "Circular dependency detected among: " + findCycleMembers(inDegree));
        }
        return order;
    }

    public List<String> resolveStopOrder() {
        List<String> start = new ArrayList<>(resolveStartOrder());
        Collections.reverse(start);
        return start;
    }

    private Comparator<String> priorityThenName() {
        return Comparator
                .comparingInt((String n) -> nodes.get(n).startPriority())
                .thenComparing(n -> n);
    }

    private Set<String> findCycleMembers(Map<String, Integer> inDegree) {
        Set<String> remaining = new HashSet<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() > 0) {
                remaining.add(e.getKey());
            }
        }
        return remaining;
    }
}
