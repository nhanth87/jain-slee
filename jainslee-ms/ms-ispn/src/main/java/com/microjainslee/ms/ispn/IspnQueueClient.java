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

import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceClient;
import com.microjainslee.ms.api.exception.ServiceCallTimeoutException;
import org.infinispan.Cache;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Remote {@link SleeServiceClient} backed by Infinispan inbox/reply caches.
 */
public final class IspnQueueClient<T> implements SleeServiceClient<T> {

    private final String serviceName;
    private final Cache<String, SleeQueueEntry> inbox;
    private final Cache<String, SleeQueueEntry> reply;
    private final IspnTransportManager transport;
    private final long callTimeoutMs;

    public IspnQueueClient(String serviceName, IspnTransportManager transport) {
        this(serviceName, transport, 10_000L);
    }

    public IspnQueueClient(String serviceName, IspnTransportManager transport, long callTimeoutMs) {
        this.serviceName = Objects.requireNonNull(serviceName);
        this.transport = Objects.requireNonNull(transport);
        this.inbox = transport.inboxCache(serviceName);
        this.reply = transport.replyCache();
        this.callTimeoutMs = callTimeoutMs;
    }

    @Override
    public SleeResponse call(SleeRequest request) {
        if (transport.stateOf(serviceName) == com.microjainslee.ms.api.ServiceState.STOPPED) {
            // Soft check — still allow call (server may be racing to READY)
        }
        SleeQueueEntry entry = SleeQueueEntry.ofRequest(request, transport.nodeId(), false);
        String inboxKey = UUID.randomUUID().toString();
        inbox.put(inboxKey, entry);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(callTimeoutMs);
        while (System.nanoTime() < deadline) {
            SleeQueueEntry resp = reply.remove(entry.correlationId());
            if (resp != null) {
                return resp.toSleeResponse();
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ServiceCallTimeoutException(
                        "Interrupted waiting for reply from '" + serviceName + "'");
            }
        }
        throw new ServiceCallTimeoutException(
                "Timed out waiting for '" + serviceName + "' (corrId=" + entry.correlationId() + ")");
    }

    @Override
    public void notify(SleeRequest request) {
        SleeQueueEntry entry = SleeQueueEntry.ofRequest(request, transport.nodeId(), true);
        inbox.put(UUID.randomUUID().toString(), entry);
    }

    @Override
    public String serviceName() {
        return serviceName;
    }
}
