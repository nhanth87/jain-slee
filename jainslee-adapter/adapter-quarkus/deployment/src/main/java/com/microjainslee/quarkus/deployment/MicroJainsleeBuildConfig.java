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

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Build-time configuration for the micro-jainslee Quarkus extension.
 *
 * <p>Resolved at build time by the Quarkus deployment module and consumed by
 * {@link MicroJainsleeProcessor} when scheduling the recorder and synthetic beans.</p>
 *
 * <p>All keys are under the {@code microjainslee.container.*} prefix, e.g.
 * {@code microjainslee.container.buffer-size=2048}. App-level keys
 * ({@code microjainslee.ai.*}, {@code microjainslee.telemetry.*}, …) stay outside
 * this mapping so SmallRye does not reject them as unknown.</p>
 */
@ConfigMapping(prefix = "microjainslee.container")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface MicroJainsleeBuildConfig {

    /**
     * ADR 0007 P1 — enable the Infinispan + JGroups cluster layer.
     *
     * <p>
     * Previously the runtime accepted {@code clusterEnabled} on
     * {@code MicroSleeConfiguration} but nothing read it at
     * {@code start()}, and this interface had no key for it at all — so
     * turning clustering on from {@code application.properties} was impossible.
     * Now the flag is honoured and the container constructs the
     * {@code ClusterManager} itself, failing fast when {@code jainslee-cluster}
     * is absent rather than silently degrading to single-JVM.
     */
    @WithName("cluster-enabled")
    @WithDefault("false")
    boolean clusterEnabled();

    /**
     * ADR 0007 P1 — stable node id for this JVM. Should be the hostname, K8s
     * pod name or instance id so log lines, metric labels and JGroups views stay
     * traceable across restarts. Also the identity used by the dialog-lease
     * fencing ({@code ownerBootEpochMs} distinguishes incarnations of the same
     * id).
     */
    @WithName("cluster-node-id")
    @WithDefault("${hostname:local}")
    String clusterNodeId();

    /** ADR 0007 P1 — JGroups transport flavour: {@code tcp} or {@code udp}. */
    @WithName("cluster-stack")
    @WithDefault("tcp")
    String clusterStack();

    /**
     * ADR 0007 P1 — comma-separated JGroups TCPPING initial hosts, e.g.
     * {@code host1[7800],host2[7800]}.
     */
    @WithName("cluster-initial-hosts")
    @WithDefault("localhost[7800]")
    String clusterInitialHosts();

    /**
     * ADR 0007 D7 — enable the RA fan-in gateway
     * ({@code 0} = disabled, preserving today's direct RingBuffer behaviour).
     */
    @WithName("fan-in-queue-capacity")
    @WithDefault("0")
    int fanInQueueCapacity();

    /** ADR 0007 D7 — events drained per fan-in iteration. */
    @WithName("fan-in-drain-batch-size")
    @WithDefault("64")
    int fanInDrainBatchSize();

    /**
     * Power-of-two ring-buffer size for the {@code EventRouter}'s LMAX Disruptor.
     * Larger values improve throughput at the cost of memory and worst-case latency.
     */
    @WithName("buffer-size")
    @WithDefault("2048")
    int bufferSize();

    /**
     * Whether the EventRouter should prefer Java virtual threads over a cached thread pool.
     */
    @WithName("prefer-virtual-threads")
    @WithDefault("true")
    boolean preferVirtualThreads();

    /**
     * Minimum number of SBB entity-pool entries kept warm.
     */
    @WithName("sbb-pool-min")
    @WithDefault("16")
    int sbbPoolMin();

    /**
     * Maximum number of SBB entity-pool entries.
     */
    @WithName("sbb-pool-max")
    @WithDefault("4096")
    int sbbPoolMax();

    /**
     * Whether to allocate SBB entity-pool entries on a per-virtual-thread basis.
     */
    @WithName("sbb-per-virtual-thread")
    @WithDefault("true")
    boolean sbbPerVirtualThread();

    /**
     * Minimum idle SBB instances kept per SBB type in the type-scoped pool.
     */
    @WithName("sbb-type-pool-min-idle")
    @WithDefault("0")
    int sbbTypePoolMinIdle();

    /**
     * Event delivery mode for the EventRouter ({@code sync}, {@code async}, …).
     */
    @WithName("event-delivery")
    @WithDefault("sync")
    String eventDelivery();

    /**
     * Register discovered {@code @SbbAnnotation} types with the container pool at startup.
     */
    @WithName("deployment.register-sbb-types")
    @WithDefault("true")
    boolean registerSbbTypes();

    /**
     * Whether to register CDI synthetic beans for {@code @SbbAnnotation} classes.
     * Default {@code false}: most apps register SBB types on {@code MicroSleeContainer}
     * with a supplier (constructor collaborators). Enabling this only works for
     * concrete SBBs with a public no-arg constructor.
     */
    @WithName("deployment.scan.enabled")
    @WithDefault("false")
    boolean scanEnabled();

    /**
     * Optional comma-separated list of class-name patterns to include during scan. Patterns
     * are matched as substrings against the fully-qualified class name. Empty means "all".
     */
    @WithName("deployment.scan.includes")
    Optional<String> scanIncludes();

    /**
     * Optional comma-separated list of class-name patterns to exclude during scan. Patterns
     * are matched as substrings against the fully-qualified class name.
     */
    @WithName("deployment.scan.excludes")
    Optional<String> scanExcludes();

    /**
     * Comma-separated list of RA entity class names to register via the 3-port contract.
     * Each class must implement both {@link com.microjainslee.api.RaEndpointPort} and
     * {@link com.microjainslee.api.RaCommandPort} (a single class serving both roles).
     * <p>
     * Example: {@code microjainslee.ra-registrations=com.example.MyHttpRa}
     */
    @WithName("ra-registrations")
    @WithDefault("")
    Optional<String> raRegistrations();

    /**
     * Event-to-SBB convergent routing mappings. Each entry maps a fully-qualified
     * {@link com.microjainslee.api.SleeEvent} class name to an SBB entity name.
     * <p>
     * Format: {@code eventClassName1=sbbName1,eventClassName2=sbbName2}
     * <p>
     * Example: {@code microjainslee.event-to-sbb-mappings=com.example.UssdBeginEvent=UssdSessionSbb}
     */
    @WithName("event-to-sbb-mappings")
    @WithDefault("")
    Optional<String> eventToSbbMappings();

    /**
     * Honor {@code @OffHeap} annotations on SBB types (off-heap CMP state,
     * docs/en/design-offheap-sbb-state.md). Default true.
     */
    @WithName("offheap-enabled")
    @WithDefault("true")
    boolean offHeapEnabled();

    /**
     * Directory for MMAP off-heap arenas when {@code @OffHeap.filePath}
     * is empty. Absent/empty → {@code $java.io.tmpdir/slee-offheap}.
     */
    @WithName("offheap-storage-dir")
    Optional<String> offHeapStorageDir();
}
