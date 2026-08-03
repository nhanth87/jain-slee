/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.sbbs;

import com.example.ms.quarkus.bootstrap.MsRuntimeHolder;
import com.example.ms.quarkus.events.MsServiceCallEvent;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.core.MicrosleeBootstrap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * App-side bridge SBB: receives {@link MsServiceCallEvent} and talks to the
 * {@code signaling} microservice through {@link MicrosleeBootstrap#client}
 * (Direct or Infinispan — transparent to this SBB).
 */
public final class MsAppBridgeSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(MsAppBridgeSbb.class);

    private final MsRuntimeHolder runtimeHolder;
    private volatile SbbLocalObject self;

    public MsAppBridgeSbb(MsRuntimeHolder runtimeHolder) {
        this.runtimeHolder = runtimeHolder;
    }

    public void bindSelf(SbbLocalObject self) {
        this.self = self;
    }

    @Override
    public void sbbCreate() {
    }

    @Override
    public void sbbActivate() {
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof MsServiceCallEvent call)) {
            return;
        }
        try {
            if (!runtimeHolder.isReady()) {
                call.response().complete(SleeResponse.error("ms runtime not ready"));
                return;
            }
            MicrosleeBootstrap boot = runtimeHolder.get().bootstrap();
            SleeRequest req = new SleeRequest(call.operation(), call.payload());
            if (call.notifyOnly()) {
                boot.client("signaling").notify(req);
                call.response().complete(SleeResponse.ok(new byte[0]));
                LOG.info("[MsAppBridge] notify signaling op={}", call.operation());
            } else {
                SleeResponse resp = boot.client("signaling").call(req);
                call.response().complete(resp);
                LOG.info("[MsAppBridge] call signaling op={} success={}",
                        call.operation(), resp.success());
            }
        } catch (RuntimeException ex) {
            LOG.error("[MsAppBridge] signaling invoke failed op={}", call.operation(), ex);
            call.response().completeExceptionally(ex);
        }
    }
}
