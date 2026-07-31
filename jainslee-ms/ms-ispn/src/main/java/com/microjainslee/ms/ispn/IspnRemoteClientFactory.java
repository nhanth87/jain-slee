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

import com.microjainslee.ms.api.RemoteClientFactory;
import com.microjainslee.ms.api.SleeServiceClient;
import com.microjainslee.ms.api.TransportType;

import java.util.Objects;

/** {@link RemoteClientFactory} that only supports {@link TransportType#INFINISPAN_QUEUE}. */
public final class IspnRemoteClientFactory implements RemoteClientFactory {

    private final IspnTransportManager transport;

    public IspnRemoteClientFactory(IspnTransportManager transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    @Override
    public SleeServiceClient<?> createRemoteClient(String serviceName, TransportType transportType) {
        if (transportType != TransportType.INFINISPAN_QUEUE) {
            throw new UnsupportedOperationException(
                    "ms-ispn only supports INFINISPAN_QUEUE, got " + transportType);
        }
        return new IspnQueueClient<>(serviceName, transport);
    }
}
