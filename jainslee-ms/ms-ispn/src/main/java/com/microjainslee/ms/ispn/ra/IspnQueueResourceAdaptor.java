/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn.ra;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceClient;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.InboxDelivery;
import com.microjainslee.ms.ispn.IspnTransportManager;
import com.microjainslee.ms.ispn.ServiceStateRecord;
import com.microjainslee.ms.ispn.SleeQueueEntry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Core MS Infinispan queue RA — owns transport ops, command dispatch, and
 * optional EVENT inbound delivery.
 */
public final class IspnQueueResourceAdaptor {

    public static final String RA_NAME = IspnQueueRaEndpoint.RA_NAME;

    private static final Logger LOG = LogManager.getLogger(IspnQueueResourceAdaptor.class);
    private static final long EVENT_REPLY_TIMEOUT_MS = 30_000L;

    private final MicrosleeBootstrap bootstrap;
    private final IspnTransportManager transport;
    private final InboundMode inboundMode;

    private volatile RaBootstrapPort sleeBootstrap;
    private volatile boolean active;

    public IspnQueueResourceAdaptor(
            MicrosleeBootstrap bootstrap,
            IspnTransportManager transport,
            DeploymentConfig config) {
        this(bootstrap, transport, config, InboundMode.HANDLER);
    }

    public IspnQueueResourceAdaptor(
            MicrosleeBootstrap bootstrap,
            IspnTransportManager transport,
            DeploymentConfig config,
            InboundMode inboundMode) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.transport = Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(config, "config");
        this.inboundMode = inboundMode == null ? InboundMode.HANDLER : inboundMode;
    }

    public InboundMode inboundMode() {
        return inboundMode;
    }

    public IspnTransportManager transport() {
        return transport;
    }

    public void activate(RaBootstrapPort bootstrapPort) {
        this.sleeBootstrap = Objects.requireNonNull(bootstrapPort, "bootstrapPort");
        this.active = true;
        LOG.info("{} activated mode={} (MS Direct/Infinispan via SleeServiceClient)",
                RA_NAME, inboundMode);
    }

    public void deactivate() {
        this.active = false;
        this.sleeBootstrap = null;
        LOG.info("{} deactivated", RA_NAME);
    }

    public boolean isActive() {
        return active;
    }

    public RaBootstrapPort sleeBootstrap() {
        return sleeBootstrap;
    }

    /**
     * EVENT-mode delivery: fire {@link MsRemoteRequestEvent} and wait for SBB reply.
     */
    public InboxDelivery eventDelivery(String serviceName) {
        return (entryKey, entry, replyWriter) -> {
            RaBootstrapPort port = sleeBootstrap;
            if (port == null) {
                throw new IllegalStateException(RA_NAME + " has no RaBootstrapPort for EVENT inbound");
            }
            MsRemoteRequestEvent event = new MsRemoteRequestEvent(
                    serviceName,
                    entry.correlationId(),
                    entry.toSleeRequest(),
                    entry.fireAndForget());
            ActivityHandle handle = port.createActivityHandle(
                    "ms-inbox-" + serviceName + "-" + entry.correlationId());
            try {
                port.fireEvent(event, handle, new ServiceAddress(serviceName));
                if (entry.fireAndForget()) {
                    return;
                }
                try {
                    SleeResponse response = event.response()
                            .get(EVENT_REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    replyWriter.write(response);
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause() == null ? ee : ee.getCause();
                    throw c instanceof Exception ex ? ex : new IllegalStateException(c);
                }
            } catch (TimeoutException te) {
                throw new IllegalStateException(
                        "EVENT inbound timed out for " + serviceName + " corr=" + entry.correlationId(),
                        te);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting EVENT reply", ie);
            } finally {
                try {
                    port.endActivity(handle);
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
        };
    }

    public void sendCommand(OutboundCommand command) {
        if (!active) {
            failFuture(command, new IllegalStateException(RA_NAME + " is not active"));
            return;
        }
        if (!(command instanceof IspnQueueCommand cmd)) {
            throw new IllegalArgumentException(
                    "Unsupported command type: " + command.getClass().getName());
        }
        switch (cmd) {
            case IspnQueueCommand.CallService call -> handleCall(call);
            case IspnQueueCommand.NotifyService notify -> handleNotify(notify);
            case IspnQueueCommand.QueryServiceState query -> handleQueryState(query);
            case IspnQueueCommand.PublishServiceState pub -> handlePublish(pub);
            case IspnQueueCommand.EnsureServiceCaches ensure -> handleEnsure(ensure);
            case IspnQueueCommand.QueryNodeId node -> handleNodeId(node);
            case IspnQueueCommand.QueryServiceStateRecord rec -> handleStateRecord(rec);
            case IspnQueueCommand.ReplyRemoteRequest reply -> handleReplyRemote(reply);
        }
    }

    private void handleCall(IspnQueueCommand.CallService call) {
        CompletableFuture<SleeResponse> reply = call.reply();
        if (reply == null) {
            throw new IllegalArgumentException("CallService.reply is required");
        }
        try {
            SleeServiceClient<?> client = bootstrap.client(call.serviceName());
            SleeRequest request = call.request() == null
                    ? new SleeRequest("ping", new byte[0])
                    : call.request();
            reply.complete(client.call(request));
        } catch (RuntimeException ex) {
            reply.completeExceptionally(ex);
        }
    }

    private void handleNotify(IspnQueueCommand.NotifyService notify) {
        CompletableFuture<Void> done = notify.done();
        try {
            SleeServiceClient<?> client = bootstrap.client(notify.serviceName());
            SleeRequest request = notify.request() == null
                    ? new SleeRequest("event", new byte[0])
                    : notify.request();
            client.notify(request);
            if (done != null) {
                done.complete(null);
            }
        } catch (RuntimeException ex) {
            if (done != null) {
                done.completeExceptionally(ex);
            } else {
                throw ex;
            }
        }
    }

    private void handleQueryState(IspnQueueCommand.QueryServiceState query) {
        CompletableFuture<ServiceState> reply = query.reply();
        if (reply == null) {
            throw new IllegalArgumentException("QueryServiceState.reply is required");
        }
        try {
            if (bootstrap.config().isLocal(query.serviceName())) {
                reply.complete(ServiceState.READY);
            } else {
                reply.complete(transport.stateOf(query.serviceName()));
            }
        } catch (RuntimeException ex) {
            reply.completeExceptionally(ex);
        }
    }

    private void handlePublish(IspnQueueCommand.PublishServiceState pub) {
        CompletableFuture<Void> done = pub.done();
        try {
            transport.publishState(pub.serviceName(), pub.state());
            if (done != null) {
                done.complete(null);
            }
        } catch (RuntimeException ex) {
            if (done != null) {
                done.completeExceptionally(ex);
            } else {
                throw ex;
            }
        }
    }

    private void handleEnsure(IspnQueueCommand.EnsureServiceCaches ensure) {
        CompletableFuture<Void> done = ensure.done();
        try {
            Collection<String> names = ensure.serviceNames();
            transport.ensureServiceCaches(names == null ? java.util.List.of() : names);
            if (done != null) {
                done.complete(null);
            }
        } catch (RuntimeException ex) {
            if (done != null) {
                done.completeExceptionally(ex);
            } else {
                throw ex;
            }
        }
    }

    private void handleNodeId(IspnQueueCommand.QueryNodeId node) {
        CompletableFuture<String> reply = node.reply();
        if (reply == null) {
            throw new IllegalArgumentException("QueryNodeId.reply is required");
        }
        try {
            reply.complete(transport.nodeId());
        } catch (RuntimeException ex) {
            reply.completeExceptionally(ex);
        }
    }

    private void handleStateRecord(IspnQueueCommand.QueryServiceStateRecord rec) {
        CompletableFuture<ServiceStateRecord> reply = rec.reply();
        if (reply == null) {
            throw new IllegalArgumentException("QueryServiceStateRecord.reply is required");
        }
        try {
            reply.complete(transport.stateCache().get(rec.serviceName()));
        } catch (RuntimeException ex) {
            reply.completeExceptionally(ex);
        }
    }

    private void handleReplyRemote(IspnQueueCommand.ReplyRemoteRequest replyCmd) {
        CompletableFuture<Void> done = replyCmd.done();
        try {
            SleeResponse response = replyCmd.response() == null
                    ? SleeResponse.error("null response")
                    : replyCmd.response();
            transport.replyCache().put(
                    replyCmd.correlationId(),
                    SleeQueueEntry.ofResponse(replyCmd.correlationId(), response));
            if (done != null) {
                done.complete(null);
            }
        } catch (RuntimeException ex) {
            if (done != null) {
                done.completeExceptionally(ex);
            } else {
                throw ex;
            }
        }
    }

    private static void failFuture(OutboundCommand command, RuntimeException ex) {
        if (command instanceof IspnQueueCommand.CallService call && call.reply() != null) {
            call.reply().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.NotifyService notify && notify.done() != null) {
            notify.done().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.QueryServiceState query && query.reply() != null) {
            query.reply().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.PublishServiceState pub && pub.done() != null) {
            pub.done().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.EnsureServiceCaches ensure && ensure.done() != null) {
            ensure.done().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.QueryNodeId node && node.reply() != null) {
            node.reply().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.QueryServiceStateRecord rec && rec.reply() != null) {
            rec.reply().completeExceptionally(ex);
        } else if (command instanceof IspnQueueCommand.ReplyRemoteRequest reply && reply.done() != null) {
            reply.done().completeExceptionally(ex);
        } else {
            throw ex;
        }
    }

    private record ServiceAddress(String serviceName) implements Address {
        @Override
        public String getAddressString() {
            return "ms://" + serviceName;
        }
    }
}
