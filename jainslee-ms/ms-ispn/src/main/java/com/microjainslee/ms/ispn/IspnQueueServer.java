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

import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Consumes inbox entries for a local service and writes replies.
 */
@Listener(clustered = true, observation = Listener.Observation.POST)
public final class IspnQueueServer {

    private static final Logger LOG = LogManager.getLogger(IspnQueueServer.class);

    private final String serviceName;
    private final Cache<String, SleeQueueEntry> inbox;
    private final Cache<String, SleeQueueEntry> reply;
    private final SleeServiceHandler handler;
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean started;

    public IspnQueueServer(String serviceName, IspnTransportManager transport, SleeServiceHandler handler) {
        this.serviceName = Objects.requireNonNull(serviceName);
        this.inbox = transport.inboxCache(serviceName);
        this.reply = transport.replyCache();
        this.handler = Objects.requireNonNull(handler);
    }

    public void start() {
        if (started) {
            return;
        }
        inbox.addListener(this);
        started = true;
        LOG.info("IspnQueueServer started for '{}'", serviceName);
    }

    public void stop() {
        if (!started) {
            return;
        }
        inbox.removeListener(this);
        vtExecutor.shutdownNow();
        started = false;
        LOG.info("IspnQueueServer stopped for '{}'", serviceName);
    }

    @CacheEntryCreated
    public void onEntryCreated(CacheEntryCreatedEvent<String, SleeQueueEntry> event) {
        // Process POST creates including origin-local (same-JVM single/local cache tests).
        if (event.isPre()) {
            return;
        }
        String key = event.getKey();
        SleeQueueEntry entry = event.getValue();
        if (entry == null) {
            return;
        }
        vtExecutor.submit(() -> processEntry(key, entry));
    }

    private void processEntry(String entryKey, SleeQueueEntry entry) {
        LOG.info("[IspnQueueServer:{}] received type={} op={} corr={} from={} faf={}",
                serviceName,
                entry.type(),
                entry.operation(),
                entry.correlationId(),
                entry.callerNode(),
                entry.fireAndForget());
        try {
            if (entry.fireAndForget()) {
                handler.invoke(entry.toSleeRequest());
                inbox.remove(entryKey);
                LOG.info("[IspnQueueServer:{}] notify done op={} corr={}",
                        serviceName, entry.operation(), entry.correlationId());
                return;
            }
            SleeResponse response = handler.invoke(entry.toSleeRequest());
            reply.put(entry.correlationId(), SleeQueueEntry.ofResponse(entry.correlationId(), response));
            inbox.remove(entryKey);
            LOG.info("[IspnQueueServer:{}] reply sent op={} corr={} success={}",
                    serviceName, entry.operation(), entry.correlationId(), response.success());
        } catch (Exception e) {
            LOG.error("Error processing queue entry {} for '{}'", entryKey, serviceName, e);
            if (!entry.fireAndForget()) {
                reply.put(entry.correlationId(),
                        SleeQueueEntry.ofError(entry.correlationId(), e.getMessage()));
            }
            inbox.remove(entryKey);
        }
    }
}
