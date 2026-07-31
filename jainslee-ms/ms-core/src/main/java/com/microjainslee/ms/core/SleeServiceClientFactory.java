/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core;

import com.microjainslee.ms.api.RemoteClientFactory;
import com.microjainslee.ms.api.SleeServiceClient;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.TransportType;
import com.microjainslee.ms.core.client.DirectServiceClient;
import com.microjainslee.ms.core.client.NoOpServiceClient;
import com.microjainslee.ms.core.config.DeploymentConfig;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selects Direct vs remote (Infinispan) clients based on {@link DeploymentConfig}.
 */
public final class SleeServiceClientFactory {

    private final DeploymentConfig config;
    private final Map<String, SleeServiceDescriptor> descriptors;
    private final Map<String, SleeServiceHandler> localHandlers = new ConcurrentHashMap<>();
    private final RemoteClientFactory remoteClientFactory;

    public SleeServiceClientFactory(
            DeploymentConfig config,
            Map<String, SleeServiceDescriptor> descriptors,
            RemoteClientFactory remoteClientFactory) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptors = Map.copyOf(descriptors);
        this.remoteClientFactory = remoteClientFactory;
    }

    public void registerLocalHandler(String serviceName, SleeServiceHandler handler) {
        localHandlers.put(serviceName, Objects.requireNonNull(handler));
    }

    public void unregisterLocalHandler(String serviceName) {
        localHandlers.remove(serviceName);
    }

    @SuppressWarnings("unchecked")
    public <T> SleeServiceClient<T> client(String serviceName) {
        SleeServiceDescriptor desc = descriptors.get(serviceName);
        boolean optional = desc == null;

        if (!config.hasService(serviceName) && optional) {
            return (SleeServiceClient<T>) new NoOpServiceClient<>(serviceName);
        }

        if (config.isLocal(serviceName)) {
            SleeServiceHandler handler = localHandlers.get(serviceName);
            if (handler == null) {
                if (optional) {
                    return (SleeServiceClient<T>) new NoOpServiceClient<>(serviceName);
                }
                throw new IllegalStateException("No local handler for service '" + serviceName + "'");
            }
            return (SleeServiceClient<T>) new DirectServiceClient<>(serviceName, handler);
        }

        TransportType transport = config.preferredTransport(
                serviceName,
                desc != null ? desc.transport() : TransportType.INFINISPAN_QUEUE);

        if (transport != TransportType.INFINISPAN_QUEUE) {
            throw new UnsupportedOperationException(
                    "Remote transport " + transport + " is not wired in MVP; use INFINISPAN_QUEUE");
        }
        if (remoteClientFactory == null) {
            throw new IllegalStateException("RemoteClientFactory required for remote service '" + serviceName + "'");
        }
        return (SleeServiceClient<T>) remoteClientFactory.createRemoteClient(serviceName, transport);
    }
}
