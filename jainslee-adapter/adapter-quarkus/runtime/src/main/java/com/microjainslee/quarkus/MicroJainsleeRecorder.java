/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus;

import com.microjainslee.api.TimerPort;
import com.microjainslee.core.EventRouter;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Quarkus build-time augmentation recorder for the embedded micro JAIN-SLEE container.
 *
 * <p>All methods on this class are invoked by Quarkus during the static-init / runtime-init
 * phases. Container + facility instances are stored in static fields that the
 * {@link MicroJainsleeProducer} reads at runtime.</p>
 */
@Recorder
public class MicroJainsleeRecorder {

    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger(MicroJainsleeRecorder.class);

    private static volatile MicroSleeContainer container;
    private static volatile EventRouter eventRouter;
    private static volatile TimerPort timerPort;
    private static volatile com.microjainslee.core.MicroSleeContainer.AcnfBackend acnf;

    /**
     * Build a fresh {@link MicroSleeContainer} using the supplied configuration and stash
     * it in the static holder for the runtime CDI producer. Called at static-init.
     *
     * @param config immutable micro-container configuration resolved at build time
     * @return runtime handle to the new container
     */
    public RuntimeValue<MicroSleeContainer> createContainer(MicroSleeConfiguration config) {
        if (config == null) {
            config = MicroSleeConfiguration.defaults();
        }
        MicroSleeContainer c = new MicroSleeContainer(config);
        container = c;
        eventRouter = c.getEventRouter();
        timerPort = c.getTimerPort();
        acnf = c.getActivityContextNamingFacility();
        LOG.info("MicroSleeContainer constructed: bufferSize={}, preferVT={}, sbbPool={}-{}, perVT={}",
                config.getEventRouterBufferSize(), config.isPreferVirtualThreads(),
                config.getSbbPoolMin(), config.getSbbPoolMax(), config.isSbbPerVirtualThread());
        return new RuntimeValue<MicroSleeContainer>(c);
    }

    /** Start the previously-created container. Idempotent. Called at runtime-init. */
    public void startContainer() {
        if (container != null) {
            LOG.info("Starting MicroSleeContainer (state={})", container.getState());
            container.start();
            LOG.info("MicroSleeContainer started (state={})", container.getState());
        } else {
            LOG.warn("startContainer() called but container is null");
        }
    }

    /** Stop the previously-started container. Called from the Quarkus shutdown hook. */
    public void stopContainer() {
        if (container != null) {
            LOG.info("Stopping MicroSleeContainer (state={})", container.getState());
            container.stop();
            LOG.info("MicroSleeContainer stopped");
        }
    }

    /**
     * Register pooled SBB types discovered at build time. Uses no-arg constructor
     * for each class; applications with CDI-managed SBBs should also call
     * {@code registerSbbType} manually with an Arc supplier if needed.
     */
    public void registerSbbTypes(java.util.List<String> classNames) {
        if (container == null || classNames == null || classNames.isEmpty()) {
            return;
        }
        for (String fqn : classNames) {
            try {
                Class<?> clazz = Class.forName(fqn);
                if (!com.microjainslee.api.Sbb.class.isAssignableFrom(clazz)) {
                    LOG.warn("Skipping non-Sbb type {}", fqn);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Class<? extends com.microjainslee.api.Sbb> sbbClass =
                        (Class<? extends com.microjainslee.api.Sbb>) clazz;
                container.registerSbbType(sbbClass, new java.util.function.Supplier<com.microjainslee.api.Sbb>() {
                    @Override
                    public com.microjainslee.api.Sbb get() {
                        try {
                            return sbbClass.getDeclaredConstructor().newInstance();
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("Cannot instantiate SBB " + fqn, e);
                        }
                    }
                });
                LOG.info("Registered pooled SBB type {}", fqn);
            } catch (ClassNotFoundException e) {
                LOG.warn("Failed to load SBB class {}: {}", fqn, e.getMessage());
            }
        }
    }

    public RuntimeValue<MicroSleeContainer> containerRuntimeValue(MicroSleeConfiguration cfg) {
        return new RuntimeValue<MicroSleeContainer>(container);
    }

    public RuntimeValue<EventRouter> eventRouterRuntimeValue(MicroSleeConfiguration cfg) {
        return new RuntimeValue<EventRouter>(eventRouter);
    }

    public RuntimeValue<TimerPort> timerPortRuntimeValue(MicroSleeConfiguration cfg) {
        return new RuntimeValue<TimerPort>(timerPort);
    }

    public RuntimeValue<com.microjainslee.core.MicroSleeContainer.AcnfBackend> acnfRuntimeValue(MicroSleeConfiguration cfg) {
        return new RuntimeValue<com.microjainslee.core.MicroSleeContainer.AcnfBackend>(acnf);
    }
}