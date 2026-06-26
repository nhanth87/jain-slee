/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus.deployment;

import com.microjainslee.api.TimerPort;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.EventRouter;
import com.microjainslee.core.InMemoryActivityContextNamingFacility;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.quarkus.MicroJainsleeProducer;
import com.microjainslee.quarkus.MicroJainsleeRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Quarkus build-step processor for the micro-jainslee extension.
 */
public class MicroJainsleeProcessor {
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger(MicroJainsleeProcessor.class);

    private static final DotName SBB_ANNOTATION = DotName.createSimple(SbbAnnotation.class.getName());
    private static final String FEATURE_NAME = "micro-jainslee";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE_NAME);
    }

    @BuildStep
    AdditionalBeanBuildItem runtimeBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(MicroJainsleeProducer.class.getName())
                .setUnremovable()
                .build();
    }

    @BuildStep
    MicroSleeConfiguration containerConfig(MicroJainsleeBuildConfig config) {
        return MicroSleeConfiguration.builder()
                .eventRouterBufferSize(powerOfTwo(config.bufferSize(), "microjainslee.buffer-size"))
                .preferVirtualThreads(config.preferVirtualThreads())
                .sbbPoolMin(config.sbbPoolMin())
                .sbbPoolMax(config.sbbPoolMax())
                .sbbPerVirtualThread(config.sbbPerVirtualThread())
                .build();
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void installContainer(MicroJainsleeRecorder recorder, MicroSleeConfiguration configuration) {
        // Recorder stashes the container in its own static fields; no holder needed.
        recorder.createContainer(configuration);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void startContainer(MicroJainsleeRecorder recorder) {
        recorder.startContainer();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    SyntheticBeanBuildItem containerSyntheticBean(MicroJainsleeRecorder recorder,
                                                 MicroSleeConfiguration configuration) {
        LOG.debug("Registering synthetic bean for MicroSleeContainer (bufferSize={}, sbbPool={}-{}, perVT={})",
                configuration.getEventRouterBufferSize(),
                configuration.getSbbPoolMin(), configuration.getSbbPoolMax(),
                configuration.isSbbPerVirtualThread());
        return SyntheticBeanBuildItem.configure(MicroSleeContainer.class)
                .scope(ApplicationScoped.class)
                .setRuntimeInit()
                .runtimeValue(recorder.containerRuntimeValue(configuration))
                .done();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    SyntheticBeanBuildItem eventRouterSyntheticBean(MicroJainsleeRecorder recorder,
                                                    MicroSleeConfiguration configuration) {
        LOG.debug("Registering synthetic bean for EventRouter");
        return SyntheticBeanBuildItem.configure(EventRouter.class)
                .scope(ApplicationScoped.class)
                .setRuntimeInit()
                .runtimeValue(recorder.eventRouterRuntimeValue(configuration))
                .done();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    SyntheticBeanBuildItem timerPortSyntheticBean(MicroJainsleeRecorder recorder,
                                                  MicroSleeConfiguration configuration) {
        LOG.debug("Registering synthetic bean for TimerPort");
        return SyntheticBeanBuildItem.configure(TimerPort.class)
                .scope(ApplicationScoped.class)
                .setRuntimeInit()
                .runtimeValue(recorder.timerPortRuntimeValue(configuration))
                .done();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    SyntheticBeanBuildItem acnfSyntheticBean(MicroJainsleeRecorder recorder,
                                             MicroSleeConfiguration configuration) {
        LOG.debug("Registering synthetic bean for InMemoryActivityContextNamingFacility");
        return SyntheticBeanBuildItem.configure(InMemoryActivityContextNamingFacility.class)
                .scope(ApplicationScoped.class)
                .setRuntimeInit()
                .runtimeValue(recorder.acnfRuntimeValue(configuration))
                .done();
    }

    /**
     * Scan the Jandex index for {@code @SbbAnnotation}-annotated classes and register a
     * synthetic bean for each. Only runs when {@code microjainslee.deployment.scan.enabled=true}.
     */
    @BuildStep
    void sbbSyntheticBeans(BuildProducer<SyntheticBeanBuildItem> beans,
                           CombinedIndexBuildItem indexBuildItem,
                           MicroJainsleeBuildConfig config) {
        if (!config.scanEnabled()) {
            LOG.info("@Sbb scan disabled (microjainslee.deployment.scan.enabled=false)");
            return;
        }
        IndexView index = indexBuildItem.getIndex();
        Set<String> includes = splitCsv(config.scanIncludes());
        Set<String> excludes = splitCsv(config.scanExcludes());

        int registered = 0;
        for (ClassInfo ci : index.getAllKnownImplementors(SBB_ANNOTATION)) {
            if (ci == null || ci.name() == null) {
                continue;
            }
            String fqn = ci.name().toString();
            if (!matches(fqn, includes, excludes)) {
                LOG.debug("Skipping @Sbb (filter mismatch): {}", fqn);
                continue;
            }
            Class<?> beanClass;
            try {
                beanClass = Class.forName(fqn);
            } catch (ClassNotFoundException e) {
                LOG.warn("Failed to load @Sbb class {}: {}", fqn, e.getMessage());
                continue;
            }
            beans.produce(SyntheticBeanBuildItem.configure(beanClass)
                    .scope(ApplicationScoped.class)
                    .unremovable()
                    .done());
            registered++;
            LOG.info("Discovered @Sbb {} -> registering synthetic bean", fqn);
        }
        LOG.info("@Sbb scan complete: {} bean(s) registered", registered);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void shutdownContainer(MicroJainsleeRecorder recorder, ShutdownContextBuildItem shutdown) {
        LOG.debug("Wiring shutdown hook for MicroSleeContainer");
        shutdown.addShutdownTask(new Runnable() {
            @Override
            public void run() {
                try {
                    recorder.stopContainer();
                } catch (Throwable t) {
                    LOG.error("MicroSleeContainer shutdown failed: {}", t.getMessage(), t);
                }
            }
        });
    }

    // ----- helpers -----

    private static int powerOfTwo(int value, String propName) {
        if (value <= 0 || Integer.bitCount(value) != 1) {
            String msg = propName + " must be a positive power of two (was " + value + ")";
            LOG.error(msg);
            throw new IllegalArgumentException(msg);
        }
        return value;
    }

    private static Set<String> splitCsv(Optional<String> csv) {
        Set<String> out = new HashSet<String>();
        if (csv.isPresent()) {
            for (String s : csv.get().split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static boolean matches(String fqn, Set<String> includes, Set<String> excludes) {
        if (!includes.isEmpty()) {
            boolean ok = false;
            for (String pat : includes) {
                if (fqn.contains(pat)) { ok = true; break; }
            }
            if (!ok) return false;
        }
        for (String pat : excludes) {
            if (fqn.contains(pat)) return false;
        }
        return true;
    }
}
