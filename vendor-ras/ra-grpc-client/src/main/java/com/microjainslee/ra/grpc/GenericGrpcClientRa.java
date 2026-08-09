/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.grpc;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.RaHaSupport;
import com.microjainslee.cluster.RaStickyRouter;
import com.microjainslee.ra.grpc.command.InvokeGrpc;
import com.microjainslee.ra.grpc.events.GrpcInvokeResponseEvent;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generic dynamic gRPC client RA — calls <b>any</b> unary method on any
 * server without generated stubs ({@code MethodDescriptor<byte[],byte[]>}
 * built at call time, channels pooled per target).
 *
 * <p>Calls run on virtual threads; the response (or error status) is
 * fired back as {@link GrpcInvokeResponseEvent} on the activity named by
 * the command's correlation id — non-blocking for the SBB entity.</p>
 */
public final class GenericGrpcClientRa {

    private static final Logger LOG = LogManager.getLogger(GenericGrpcClientRa.class);

    /** Identity marshaller — raw message bytes through. */
    public static final MethodDescriptor.Marshaller<byte[]> BYTES_MARSHALLER =
            new MethodDescriptor.Marshaller<>() {
                @Override
                public InputStream stream(byte[] value) {
                    return new ByteArrayInputStream(value == null ? new byte[0] : value);
                }

                @Override
                public byte[] parse(InputStream stream) {
                    try {
                        return stream.readAllBytes();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read gRPC message", e);
                    }
                }
            };

    private RaBootstrapPort bootstrap;
    private long defaultDeadlineMillis = 10_000L;
    private boolean usePlaintext = true;

    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, ActivityHandle> activities = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private ExecutorService callPool;
    private volatile ClusterManager clusterManager;
    private volatile RaHaSupport haSupport;
    private volatile Object pendingCheckpointContainer;

    public void setBootstrapPort(RaBootstrapPort bootstrap) {
        this.bootstrap = bootstrap;
    }

    public void setClusterManager(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    public void setMicroSleeContainer(Object container) {
        pendingCheckpointContainer = container;
        RaHaSupport ha = haSupport;
        if (ha != null) {
            ha.checkpointBridge().bindContainer(container);
        }
    }

    public RaHaSupport haSupport() {
        return haSupport;
    }

    public void setDefaultDeadlineMillis(long millis) {
        this.defaultDeadlineMillis = millis;
    }

    /** Plaintext by default; disable to use the system TLS defaults. */
    public void setUsePlaintext(boolean plaintext) {
        this.usePlaintext = plaintext;
    }

    public boolean isActive() { return active.get(); }
    public int openChannelCount() { return channels.size(); }

    public void raActive() {
        if (!active.compareAndSet(false, true)) return;
        callPool = Executors.newVirtualThreadPerTaskExecutor();
        ClusterManager cm = clusterManager;
        RaHaSupport ha = cm != null
                ? RaHaSupport.create(cm, "grpc-client")
                : RaHaSupport.localOnly("grpc-client", "local-grpc-client");
        Object pending = pendingCheckpointContainer;
        if (pending != null) {
            ha.checkpointBridge().bindContainer(pending);
        }
        haSupport = ha;
        LOG.info("[grpc-client-ra] ACTIVE (dynamic bytes-level unary calls)");
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) return;
        RaHaSupport ha = haSupport;
        haSupport = null;
        if (ha != null) {
            ha.stopStickyBus();
        }
        if (callPool != null) { callPool.shutdown(); callPool = null; }
        for (ManagedChannel channel : channels.values()) {
            channel.shutdown();
        }
        for (ManagedChannel channel : channels.values()) {
            try {
                channel.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        channels.clear();
        activities.clear();
        LOG.info("[grpc-client-ra] INACTIVE");
    }

    public void sendOutbound(InvokeGrpc command) {
        ExecutorService pool = this.callPool;
        if (!active.get() || pool == null) {
            LOG.warn("[grpc-client-ra] not active — InvokeGrpc({}) dropped", command.fullMethod());
            return;
        }
        RaHaSupport ha = haSupport;
        if (ha != null && command.correlationId() != null) {
            RaStickyRouter.Decision d = ha.decide(command.correlationId(), true, true);
            if (d.action() == RaStickyRouter.Action.REJECT) {
                LOG.warn("[grpc-client-ra] sync-path REJECT correlation={}: {}",
                        command.correlationId(), d.reason());
                return;
            }
        }
        pool.submit(() -> doInvoke(command));
    }

    private void doInvoke(InvokeGrpc command) {
        RaHaSupport ha = haSupport;
        if (ha != null && command.correlationId() != null) {
            String target = command.target() == null ? "" : command.target();
            String method = command.fullMethod() == null ? "" : command.fullMethod();
            ha.onOpened(command.correlationId(), "IN_FLIGHT",
                    Map.of("target", target, "method", method));
            ha.checkpointSbb(command.correlationId());
        }
        GrpcInvokeResponseEvent event;
        try {
            ManagedChannel channel = channels.computeIfAbsent(command.target(), target -> {
                String[] hostPort = target.split(":", 2);
                NettyChannelBuilder builder = NettyChannelBuilder.forAddress(
                        hostPort[0], hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443);
                if (usePlaintext) {
                    builder.usePlaintext();
                }
                return builder.build();
            });
            MethodDescriptor<byte[], byte[]> method = MethodDescriptor
                    .<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(command.fullMethod())
                    .setRequestMarshaller(BYTES_MARSHALLER)
                    .setResponseMarshaller(BYTES_MARSHALLER)
                    .build();
            long deadline = command.deadlineMillis() > 0
                    ? command.deadlineMillis() : defaultDeadlineMillis;
            byte[] response = ClientCalls.blockingUnaryCall(channel, method,
                    CallOptions.DEFAULT.withDeadlineAfter(deadline, TimeUnit.MILLISECONDS),
                    command.payload());
            event = new GrpcInvokeResponseEvent(command.correlationId(), command.target(),
                    command.fullMethod(), response, 0, null);
        } catch (StatusRuntimeException e) {
            Status status = e.getStatus();
            event = new GrpcInvokeResponseEvent(command.correlationId(), command.target(),
                    command.fullMethod(), null, status.getCode().value(),
                    status.getDescription());
        } catch (RuntimeException e) {
            event = new GrpcInvokeResponseEvent(command.correlationId(), command.target(),
                    command.fullMethod(), null, Status.Code.UNKNOWN.value(), e.getMessage());
        }
        if (ha != null && command.correlationId() != null) {
            ha.onTouched(command.correlationId(),
                    event.statusCode() == 0 ? "OK" : "ERROR",
                    Map.of("statusCode", String.valueOf(event.statusCode())));
            ha.checkpointSbb(command.correlationId());
        }
        fireResponse(event);
    }

    private void fireResponse(GrpcInvokeResponseEvent event) {
        RaBootstrapPort bp = this.bootstrap;
        if (bp == null) {
            LOG.warn("[grpc-client-ra] bootstrapPort not set — response for {} dropped",
                    event.correlationId());
            return;
        }
        ActivityHandle handle = activities.computeIfAbsent(event.correlationId(),
                bp::createActivityHandle);
        bp.fireEvent(event, handle, null);
    }

    /** End the correlation activity when the application flow is done. */
    public void endActivity(String correlationId) {
        ActivityHandle handle = activities.remove(correlationId);
        if (handle != null && bootstrap != null) {
            bootstrap.endActivity(handle);
        }
    }
}
