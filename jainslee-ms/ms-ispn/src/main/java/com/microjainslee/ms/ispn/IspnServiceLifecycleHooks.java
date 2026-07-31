/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn;

import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.core.ServiceLifecycleHooks;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Lifecycle hooks that start an {@link IspnQueueServer} per local service
 * and publish readiness into the ISPN state cache.
 *
 * <p>The {@code handlerFactory} creates the business handler (typically
 * wrapping an RA command/event adapter). Container {@code registerRa}
 * remains the adapter's responsibility via a decorating hooks instance.
 */
public final class IspnServiceLifecycleHooks implements ServiceLifecycleHooks {

    private final IspnTransportManager transport;
    private final Function<SleeServiceDescriptor, SleeServiceHandler> handlerFactory;
    private final Map<String, IspnQueueServer> servers = new ConcurrentHashMap<>();
    private final ServiceLifecycleHooks delegate;

    public IspnServiceLifecycleHooks(
            IspnTransportManager transport,
            Function<SleeServiceDescriptor, SleeServiceHandler> handlerFactory) {
        this(transport, handlerFactory, null);
    }

    public IspnServiceLifecycleHooks(
            IspnTransportManager transport,
            Function<SleeServiceDescriptor, SleeServiceHandler> handlerFactory,
            ServiceLifecycleHooks delegate) {
        this.transport = Objects.requireNonNull(transport);
        this.handlerFactory = Objects.requireNonNull(handlerFactory);
        this.delegate = delegate;
    }

    @Override
    public SleeServiceHandler activate(SleeServiceDescriptor descriptor) throws Exception {
        SleeServiceHandler handler;
        if (delegate != null) {
            handler = delegate.activate(descriptor);
        } else {
            handler = handlerFactory.apply(descriptor);
        }
        IspnQueueServer server = new IspnQueueServer(descriptor.name(), transport, handler);
        server.start();
        servers.put(descriptor.name(), server);
        return handler;
    }

    @Override
    public void deactivate(SleeServiceDescriptor descriptor) throws Exception {
        IspnQueueServer server = servers.remove(descriptor.name());
        if (server != null) {
            server.stop();
        }
        if (delegate != null) {
            delegate.deactivate(descriptor);
        }
    }

    @Override
    public void publishState(String serviceName, ServiceState state) {
        transport.publishState(serviceName, state);
        if (delegate != null) {
            delegate.publishState(serviceName, state);
        }
    }
}
