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

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.ispn.ServiceStateRecord;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Outbound commands for {@code ispn-queue-ra} — full MS Infinispan transport surface.
 */
public sealed interface IspnQueueCommand extends OutboundCommand
        permits IspnQueueCommand.CallService,
                IspnQueueCommand.NotifyService,
                IspnQueueCommand.QueryServiceState,
                IspnQueueCommand.PublishServiceState,
                IspnQueueCommand.EnsureServiceCaches,
                IspnQueueCommand.QueryNodeId,
                IspnQueueCommand.QueryServiceStateRecord,
                IspnQueueCommand.ReplyRemoteRequest {

    record CallService(
            String serviceName,
            SleeRequest request,
            CompletableFuture<SleeResponse> reply) implements IspnQueueCommand {
    }

    record NotifyService(
            String serviceName,
            SleeRequest request,
            CompletableFuture<Void> done) implements IspnQueueCommand {
    }

    record QueryServiceState(
            String serviceName,
            CompletableFuture<ServiceState> reply) implements IspnQueueCommand {
    }

    record PublishServiceState(
            String serviceName,
            ServiceState state,
            CompletableFuture<Void> done) implements IspnQueueCommand {
    }

    record EnsureServiceCaches(
            Collection<String> serviceNames,
            CompletableFuture<Void> done) implements IspnQueueCommand {
    }

    record QueryNodeId(CompletableFuture<String> reply) implements IspnQueueCommand {
    }

    record QueryServiceStateRecord(
            String serviceName,
            CompletableFuture<ServiceStateRecord> reply) implements IspnQueueCommand {
    }

    /**
     * Write a reply into the shared reply cache (EVENT inbound or advanced SBB use).
     */
    record ReplyRemoteRequest(
            String correlationId,
            SleeResponse response,
            CompletableFuture<Void> done) implements IspnQueueCommand {
    }
}
