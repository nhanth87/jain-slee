/*
 * micro-jainslee 1.2.0
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
 * Consumes inbox entries for a local service and writes replies via
 * {@link InboxDelivery} (HANDLER or EVENT).
 */
@Listener(clustered = true, observation = Listener.Observation.POST)
public final class IspnQueueServer {

    private static final Logger LOG = LogManager.getLogger(IspnQueueServer.class);

    private final String serviceName;
    private final Cache<String, SleeQueueEntry> inbox;
    private final Cache<String, SleeQueueEntry> reply;
    private final InboxDelivery delivery;
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean started;

    public IspnQueueServer(String serviceName, IspnTransportManager transport, SleeServiceHandler handler) {
        this(serviceName, transport, handlerDelivery(Objects.requireNonNull(handler, "handler")));
    }

    public IspnQueueServer(String serviceName, IspnTransportManager transport, InboxDelivery delivery) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.inbox = transport.inboxCache(serviceName);
        this.reply = transport.replyCache();
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    public static InboxDelivery handlerDelivery(SleeServiceHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return (entryKey, entry, replyWriter) -> {
            if (entry.fireAndForget()) {
                handler.invoke(entry.toSleeRequest());
                return;
            }
            SleeResponse response = handler.invoke(entry.toSleeRequest());
            replyWriter.write(response);
        };
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
            ReplyWriter replyWriter = response -> {
                if (!entry.fireAndForget()) {
                    reply.put(entry.correlationId(),
                            SleeQueueEntry.ofResponse(entry.correlationId(), response));
                }
            };
            delivery.deliver(entryKey, entry, replyWriter);
            inbox.remove(entryKey);
            if (entry.fireAndForget()) {
                LOG.info("[IspnQueueServer:{}] notify done op={} corr={}",
                        serviceName, entry.operation(), entry.correlationId());
            } else {
                LOG.info("[IspnQueueServer:{}] reply sent op={} corr={}",
                        serviceName, entry.operation(), entry.correlationId());
            }
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
