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
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceClient;
import com.microjainslee.ms.api.exception.ServiceCallTimeoutException;
import com.microjainslee.ms.api.exception.ServiceUnavailableException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Remote {@link SleeServiceClient} backed by Infinispan inbox/reply caches.
 *
 * <p>Fail-hard contract: {@link #call} / {@link #notify} require the peer
 * service to be {@link ServiceState#READY} (or {@link ServiceState#DEGRADED}).
 * Missing peer or timeout never returns a synthetic success response.
 */
public final class IspnQueueClient<T> implements SleeServiceClient<T> {

    private static final Logger LOG = LogManager.getLogger(IspnQueueClient.class);

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
        assertPeerReady("call");
        SleeQueueEntry entry = SleeQueueEntry.ofRequest(request, transport.nodeId(), false);
        String inboxKey = UUID.randomUUID().toString();
        LOG.info("[IspnQueueClient:{}] enqueue op={} corr={} from={} timeoutMs={}",
                serviceName, request.operation(), entry.correlationId(),
                transport.nodeId(), callTimeoutMs);
        inbox.put(inboxKey, entry);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(callTimeoutMs);
        while (System.nanoTime() < deadline) {
            // Peer may flip STOPPED while we wait (Ctrl+C / crash publish).
            ServiceState live = transport.stateOf(serviceName);
            if (live != ServiceState.READY && live != ServiceState.DEGRADED) {
                inbox.remove(inboxKey);
                throw new ServiceUnavailableException(
                        "Service '" + serviceName + "' became " + live
                                + " while waiting for reply (corrId=" + entry.correlationId() + ")");
            }
            SleeQueueEntry resp = reply.remove(entry.correlationId());
            if (resp != null) {
                if (resp.type() != SleeQueueEntry.EntryType.RESPONSE
                        && resp.type() != SleeQueueEntry.EntryType.ERROR) {
                    inbox.remove(inboxKey);
                    throw new ServiceUnavailableException(
                            "Invalid reply type " + resp.type() + " for '" + serviceName
                                    + "' (corrId=" + entry.correlationId() + ")");
                }
                return resp.toSleeResponse();
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                inbox.remove(inboxKey);
                throw new ServiceCallTimeoutException(
                        "Interrupted waiting for reply from '" + serviceName + "'");
            }
        }
        inbox.remove(inboxKey);
        throw new ServiceCallTimeoutException(
                "Timed out waiting for '" + serviceName + "' (corrId=" + entry.correlationId()
                        + ", timeoutMs=" + callTimeoutMs + ")");
    }

    @Override
    public void notify(SleeRequest request) {
        assertPeerReady("notify");
        SleeQueueEntry entry = SleeQueueEntry.ofRequest(request, transport.nodeId(), true);
        LOG.info("[IspnQueueClient:{}] notify op={} corr={} from={}",
                serviceName, request.operation(), entry.correlationId(), transport.nodeId());
        inbox.put(UUID.randomUUID().toString(), entry);
    }

    @Override
    public String serviceName() {
        return serviceName;
    }

    private void assertPeerReady(String op) {
        ServiceState state = transport.stateOf(serviceName);
        // Only READY (or DEGRADED) may accept traffic. STOPPED / STARTING /
        // missing record (mapped to STOPPED) must fail immediately — never
        // put an inbox entry that will silently succeed or hang.
        if (state != ServiceState.READY && state != ServiceState.DEGRADED) {
            throw new ServiceUnavailableException(
                    "Service '" + serviceName + "' is " + state
                            + " — refusing " + op + " (no READY peer)");
        }
    }
}
