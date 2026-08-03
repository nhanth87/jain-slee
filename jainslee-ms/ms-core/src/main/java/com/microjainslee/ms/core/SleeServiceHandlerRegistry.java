/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core;

import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.SleeServiceHandlerProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * n-n binding table between services and handlers, owned by jainslee-ms so
 * applications never hand-write {@code switch (desc.name())} glue.
 *
 * <p>Topology is many-to-many:
 * <ul>
 *   <li>one handler may be {@link #register(Collection, SleeServiceHandler)
 *       registered} for many services;</li>
 *   <li>one service may hold many bindings — requests are routed per
 *       {@link SleeRequest#operation()}, operation-specific bindings first.</li>
 * </ul>
 *
 * <p>Binding sources, strongest first (consistent with "programmatic beats
 * descriptor" elsewhere in the runtime):
 * <ol>
 *   <li>programmatic {@code register(...)} calls;</li>
 *   <li>{@link SleeServiceHandlerProvider} via {@link ServiceLoader};</li>
 *   <li>the {@code @SleeService} class itself when it implements
 *       {@link SleeServiceHandler} (wildcard binding).</li>
 * </ol>
 *
 * <p>{@link #resolve(SleeServiceDescriptor)} fails fast when a local service
 * has no binding at all, so misconfiguration surfaces at start, not as a
 * silent "unknown service" reply at traffic time.
 */
public final class SleeServiceHandlerRegistry {

    private static final int TIER_PROGRAMMATIC = 0;
    private static final int TIER_PROVIDER = 1;
    private static final int TIER_SELF = 2;

    private record Binding(
            Set<String> operations,
            int tier,
            int priority,
            SleeServiceHandler handler,
            String source) {

        boolean matches(String operation) {
            return operations.isEmpty() || operations.contains(operation);
        }

        boolean operationSpecific() {
            return !operations.isEmpty();
        }
    }

    private static final Comparator<Binding> BEST_FIRST = Comparator
            .comparing((Binding b) -> !b.operationSpecific())
            .thenComparingInt(Binding::tier)
            .thenComparingInt(Binding::priority);

    private final Map<String, List<Binding>> byService = new ConcurrentHashMap<>();

    /**
     * Discover bindings for the given descriptors: {@link ServiceLoader}
     * providers plus self-handling {@code @SleeService} classes.
     */
    public static SleeServiceHandlerRegistry discover(List<SleeServiceDescriptor> descriptors) {
        SleeServiceHandlerRegistry registry = new SleeServiceHandlerRegistry();
        registry.loadProviders(descriptors);
        registry.loadSelfHandlers(descriptors);
        return registry;
    }

    /** Register a wildcard (all operations) handler for one service. */
    public SleeServiceHandlerRegistry register(String serviceName, SleeServiceHandler handler) {
        return register(serviceName, List.of(), 100, handler);
    }

    /** n-n convenience: one handler serving many services. */
    public SleeServiceHandlerRegistry register(Collection<String> serviceNames, SleeServiceHandler handler) {
        for (String name : serviceNames) {
            register(name, handler);
        }
        return this;
    }

    /**
     * Register a handler for specific operations of one service. Empty
     * {@code operations} means all. Lower {@code priority} wins on ties.
     */
    public SleeServiceHandlerRegistry register(
            String serviceName,
            Collection<String> operations,
            int priority,
            SleeServiceHandler handler) {
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(handler, "handler");
        add(serviceName, new Binding(
                Set.copyOf(operations), TIER_PROGRAMMATIC, priority, handler,
                "programmatic:" + handler.getClass().getName()));
        return this;
    }

    /** Ordered, human-readable binding sources per service (for diagnostics). */
    public Map<String, List<String>> describe() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        byService.forEach((service, bindings) -> {
            List<Binding> sorted = new ArrayList<>(bindings);
            sorted.sort(BEST_FIRST);
            out.put(service, sorted.stream()
                    .map(b -> b.source() + (b.operationSpecific() ? " ops=" + b.operations() : " ops=*"))
                    .toList());
        });
        return out;
    }

    /**
     * Resolve the dispatch handler for a local service. Single wildcard
     * binding short-circuits to the handler itself; otherwise returns a
     * per-operation router over all bindings.
     */
    public SleeServiceHandler resolve(SleeServiceDescriptor descriptor) {
        String service = descriptor.name();
        List<Binding> bindings = byService.get(service);
        if (bindings == null || bindings.isEmpty()) {
            throw new IllegalStateException(
                    "No handler binding for service '" + service + "'. Either register one "
                    + "programmatically, provide a SleeServiceHandlerProvider via "
                    + "META-INF/services, or let " + descriptor.serviceClass().getName()
                    + " implement SleeServiceHandler.");
        }
        List<Binding> snapshot = List.copyOf(bindings);
        if (snapshot.size() == 1 && !snapshot.get(0).operationSpecific()) {
            return snapshot.get(0).handler();
        }
        return request -> dispatch(service, snapshot, request);
    }

    private SleeResponse dispatch(String service, List<Binding> bindings, SleeRequest request)
            throws Exception {
        String operation = request.operation() == null ? "" : request.operation();
        Binding best = bindings.stream()
                .filter(b -> b.matches(operation))
                .min(BEST_FIRST)
                .orElseThrow(() -> new IllegalStateException(
                        "No handler binding for service '" + service + "' operation '"
                        + operation + "'"));
        return best.handler().invoke(request);
    }

    private void loadProviders(List<SleeServiceDescriptor> descriptors) {
        Map<String, SleeServiceDescriptor> byName = new LinkedHashMap<>();
        for (SleeServiceDescriptor d : descriptors) {
            byName.put(d.name(), d);
        }
        for (SleeServiceHandlerProvider provider
                : ServiceLoader.load(SleeServiceHandlerProvider.class)) {
            for (String service : provider.serviceNames()) {
                SleeServiceDescriptor descriptor = byName.get(service);
                if (descriptor == null) {
                    continue; // provider targets a service not deployed here
                }
                add(service, new Binding(
                        Set.copyOf(provider.operations(service)),
                        TIER_PROVIDER,
                        provider.priority(),
                        provider.create(descriptor),
                        "provider:" + provider.getClass().getName()));
            }
        }
    }

    private void loadSelfHandlers(List<SleeServiceDescriptor> descriptors) {
        for (SleeServiceDescriptor descriptor : descriptors) {
            Class<?> type = descriptor.serviceClass();
            if (type == null || !SleeServiceHandler.class.isAssignableFrom(type)) {
                continue;
            }
            SleeServiceHandler handler;
            try {
                handler = (SleeServiceHandler) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Service class " + type.getName() + " implements SleeServiceHandler "
                        + "but has no accessible no-arg constructor", e);
            }
            add(descriptor.name(), new Binding(
                    Set.of(), TIER_SELF, 100, handler, "self:" + type.getName()));
        }
    }

    private void add(String serviceName, Binding binding) {
        byService.computeIfAbsent(serviceName, n -> new ArrayList<>()).add(binding);
    }
}
