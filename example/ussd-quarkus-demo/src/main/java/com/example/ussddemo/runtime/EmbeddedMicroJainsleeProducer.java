/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.runtime;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Boots the embedded {@link MicroSleeContainer} inside Quarkus.
 */
@ApplicationScoped
public final class EmbeddedMicroJainsleeProducer {

    private static final Logger LOG = Logger.getLogger(EmbeddedMicroJainsleeProducer.class);

    @ConfigProperty(name = "microjainslee.buffer-size", defaultValue = "2048")
    int bufferSize;

    @ConfigProperty(name = "microjainslee.prefer-virtual-threads", defaultValue = "true")
    boolean preferVirtualThreads;

    @ConfigProperty(name = "microjainslee.sbb-pool-min", defaultValue = "16")
    int sbbPoolMin;

    @ConfigProperty(name = "microjainslee.sbb-pool-max", defaultValue = "4096")
    int sbbPoolMax;

    @ConfigProperty(name = "microjainslee.sbb-per-virtual-thread", defaultValue = "true")
    boolean sbbPerVirtualThread;

    private MicroSleeContainer container;

    @PostConstruct
    void start() {
        MicroSleeConfiguration configuration = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(bufferSize)
                .preferVirtualThreads(preferVirtualThreads)
                .sbbPoolMin(sbbPoolMin)
                .sbbPoolMax(sbbPoolMax)
                .sbbPerVirtualThread(sbbPerVirtualThread)
                .build();
        container = new MicroSleeContainer(configuration);
        container.start();
        LOG.info("Embedded MicroSleeContainer started (bufferSize=" + bufferSize + ")");
    }

    @PreDestroy
    void stop() {
        if (container != null) {
            container.stop();
            LOG.info("Embedded MicroSleeContainer stopped");
        }
    }

    public MicroSleeContainer container() {
        return container;
    }
}
