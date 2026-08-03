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

import java.util.concurrent.CompletableFuture;

/**
 * Outbound commands for {@link IspnQueueRaEndpoint} ({@code ispn-queue-ra}).
 *
 * <p>Sync results are delivered by completing the supplied futures inside
 * {@code sendCommand} (void RA port + call/notify/state semantics).
 */
public sealed interface IspnQueueCommand extends OutboundCommand
        permits IspnQueueCommand.CallService,
                IspnQueueCommand.NotifyService,
                IspnQueueCommand.QueryServiceState {

    /**
     * Request/response MS call (Direct or Infinispan via bootstrap client).
     */
    record CallService(
            String serviceName,
            SleeRequest request,
            CompletableFuture<SleeResponse> reply) implements IspnQueueCommand {
    }

    /**
     * Fire-and-forget MS notify.
     */
    record NotifyService(
            String serviceName,
            SleeRequest request,
            CompletableFuture<Void> done) implements IspnQueueCommand {
    }

    /**
     * Read published service state (ISPN state cache / local ready).
     */
    record QueryServiceState(
            String serviceName,
            CompletableFuture<ServiceState> reply) implements IspnQueueCommand {
    }
}
