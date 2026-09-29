/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

/**
 * Immutable configuration for the embedded micro JAIN-SLEE container.
 */
public final class MicroSleeConfiguration {

    private static final int DEFAULT_RING_BUFFER_SIZE = 2048;
    private static final int DEFAULT_SBB_POOL_MIN = 16;
    private static final int DEFAULT_SBB_POOL_MAX = 4096;

    private static final int DEFAULT_SBB_TYPE_POOL_MIN_IDLE = 0;

    // Production P2.1 — cluster layer defaults. Default mode is local
    // (R&D / single-JVM) so the kernel does not pay the JGroups cost unless
    // the embedder explicitly opts in.
    // ADR 0007 D7 / P4-c — RA fan-in gateway. Disabled by default so today's
    // behaviour (RAs publish straight into the disruptor ring) is unchanged.
    private static final int DEFAULT_FAN_IN_QUEUE_CAPACITY = 0;
    private static final int DEFAULT_FAN_IN_DRAIN_BATCH_SIZE = 64;

    private static final boolean DEFAULT_CLUSTER_ENABLED = false;
    private static final String DEFAULT_CLUSTER_STACK = "tcp";
    private static final String DEFAULT_CLUSTER_INITIAL_HOSTS = "localhost[7800]";

    // Production P3 — local SBB supervision defaults. Supervision is a
    // single-JVM safety net: it never contacts a cluster, and it is on by
    // default because a wedged SBB entity (per-SBB virtual thread stuck in
    // user code) is undetectable from application code.
    private static final boolean DEFAULT_SBB_SUPERVISION_ENABLED = true;
    private static final int DEFAULT_SBB_RESTART_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_SBB_RESTART_BACKOFF_BASE_MS = 100L;
    private static final long DEFAULT_SBB_RESTART_BACKOFF_MAX_MS = 3_200L;
    private static final int DEFAULT_SBB_RESTART_REPLAY_CAPACITY = 256;

    private final int eventRouterBufferSize;
    private final int fanInQueueCapacity;
    private final int fanInDrainBatchSize;
    private final boolean preferVirtualThreads;
    private final int sbbPoolMin;
    private final int sbbPoolMax;
    private final boolean sbbPerVirtualThread;
    private final int sbbTypePoolMinIdle;
    private final EventDeliveryMode eventDeliveryMode;
    private final boolean txEnabled;
    private final boolean clusterEnabled;
    private final String clusterStack;
    private final String clusterInitialHosts;
    private final String nodeId;
    // Production P1.4 — Javassist CMP codegen (S2). When the optional
    // jainslee-codegen module is on the runtime classpath the pool uses it
    // to turn abstract SBB classes into concrete ones at acquire() time.
    // Default behaviour keeps the existing reflective CmpAccessorInvoker
    // path so existing embedders see no change.
    private final boolean codegenEnabled;
    private final String deployDir;
    private final boolean tracePinnedThreads;
    /** Honor @OffHeap annotations (design: docs/en/design-offheap-sbb-state.md). */
    private final boolean offHeapEnabled;
    /** Default directory for MMAP arenas when @OffHeap.filePath is empty. */
    private final String offHeapStorageDir;
    /** Production P3 — local SBB supervision (restart wedged/crash-looping entities). */
    private final boolean sbbSupervisionEnabled;
    /** Production P3 — supervised restarts allowed per entity id before DEAD. */
    private final int sbbRestartMaxAttempts;
    /** Production P3 — first supervised-restart delay (exponential base). */
    private final long sbbRestartBackoffBaseMs;
    /** Production P3 — ceiling for the exponential backoff delay. */
    private final long sbbRestartBackoffMaxMs;
    /**
     * Production P3 (M3) — per-entity bound of the parked-event replay
     * buffer: events routed to an entity whose supervised restart is in
     * flight are parked (up to this many) and re-routed to the fresh
     * entity once the restart lands. {@code 0} disables parking entirely
     * (pre-M3 behaviour).
     */
    private final int sbbRestartReplayCapacity;

    private MicroSleeConfiguration(Builder builder) {
        this.eventRouterBufferSize = builder.eventRouterBufferSize;
        this.fanInQueueCapacity = builder.fanInQueueCapacity;
        this.fanInDrainBatchSize = builder.fanInDrainBatchSize;
        this.preferVirtualThreads = builder.preferVirtualThreads;
        this.sbbPoolMin = builder.sbbPoolMin;
        this.sbbPoolMax = builder.sbbPoolMax;
        this.sbbPerVirtualThread = builder.sbbPerVirtualThread;
        this.sbbTypePoolMinIdle = builder.sbbTypePoolMinIdle;
        this.eventDeliveryMode = builder.eventDeliveryMode;
        this.txEnabled = builder.txEnabled;
        this.clusterEnabled = builder.clusterEnabled;
        this.clusterStack = builder.clusterStack;
        this.clusterInitialHosts = builder.clusterInitialHosts;
        this.nodeId = builder.nodeId;
        this.codegenEnabled = builder.codegenEnabled;
        this.deployDir = builder.deployDir;
        this.tracePinnedThreads = builder.tracePinnedThreads;
        this.offHeapEnabled = builder.offHeapEnabled;
        this.offHeapStorageDir = builder.offHeapStorageDir;
        this.sbbSupervisionEnabled = builder.sbbSupervisionEnabled;
        this.sbbRestartMaxAttempts = builder.sbbRestartMaxAttempts;
        this.sbbRestartBackoffBaseMs = builder.sbbRestartBackoffBaseMs;
        this.sbbRestartBackoffMaxMs = builder.sbbRestartBackoffMaxMs;
        this.sbbRestartReplayCapacity = builder.sbbRestartReplayCapacity;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MicroSleeConfiguration defaults() {
        return builder().build();
    }

    public int getEventRouterBufferSize() {
        return eventRouterBufferSize;
    }

    /**
     * ADR 0007 D7 / P4-c — capacity of the RA fan-in gateway queue.
     * <p>
     * {@code 0} (the default) disables the gateway entirely and preserves
     * today's behaviour exactly: RAs publish straight into the disruptor ring.
     * A positive value makes the container create a {@code RaFanInGateway},
     * which batches publishes and applies back-pressure to RAs when full.
     */
    public int getFanInQueueCapacity() {
        return fanInQueueCapacity;
    }

    /**
     * ADR 0007 D7 / P4-c — events drained per fan-in iteration. Higher batches
     * amortise the ring-buffer CAS across many RA publishes.
     */
    public int getFanInDrainBatchSize() {
        return fanInDrainBatchSize;
    }

    public boolean isPreferVirtualThreads() {
        return preferVirtualThreads;
    }

    public int getSbbPoolMin() {
        return sbbPoolMin;
    }

    public int getSbbPoolMax() {
        return sbbPoolMax;
    }

    public boolean isSbbPerVirtualThread() {
        return sbbPerVirtualThread;
    }

    public int getSbbTypePoolMinIdle() {
        return sbbTypePoolMinIdle;
    }

    public EventDeliveryMode getEventDeliveryMode() {
        return eventDeliveryMode;
    }

    /**
     * Production P1.2 — when {@code true}, the container looks up
     * {@code com.microjainslee.tx.JtaTransactionManager} reflectively on
     * classpath and wraps each SBB event delivery in a JTA transaction.
     * Default {@code false} preserves the R&D behaviour (logical undo stack
     * in {@link SbbTransactionContext}, no JTA).
     */
    public boolean isTxEnabled() {
        return txEnabled;
    }

    /**
     * Production P2.1 — when {@code true}, the container looks up
     * {@code com.microjainslee.cluster.ClusterManager} reflectively on
     * the classpath and binds the resulting instance through
     * {@code MicroSleeContainer.bindCluster(Object)}. The cluster layer
     * is wired only when this flag is enabled, so the default
     * {@code false} keeps the kernel single-JVM (R&amp;D behaviour).
     */
    public boolean isClusterEnabled() {
        return clusterEnabled;
    }

    /**
     * Production P2.1 — JGroups transport flavour. Accepted values are
     * {@code "tcp"} (default) and {@code "udp"}. Selects the
     * {@code jgroups-tcp.xml} or {@code jgroups-udp.xml} configuration
     * file that ships inside the JGroups jar. Ignored when
     * {@link #isClusterEnabled()} is {@code false}.
     */
    public String getClusterStack() {
        return clusterStack;
    }

    /**
     * Production P2.1 — comma-separated JGroups discovery initial hosts,
     * e.g. {@code "host1[7800],host2[7800]"}. Forwarded to JGroups as
     * the {@code initial_hosts} property. Ignored when
     * {@link #isClusterEnabled()} is {@code false}.
     */
    public String getClusterInitialHosts() {
        return clusterInitialHosts;
    }

    /**
     * Production P2.1 — stable node id for this JVM. When {@code null}
     * (the default) the {@code ClusterManager} generates a short
     * random UUID at construction time. Production embedders should
     * set this to a stable value (hostname, K8s pod name, etc.) so log
     * lines and JGroups views are traceable across restarts.
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Production P1.4 — Javassist CMP codegen enabled. When {@code true}
     * the container looks up {@code com.microjainslee.codegen.JavassistDeployTimeCodegen}
     * reflectively at start time; if the module is on the runtime classpath
     * the pool will use it to turn abstract SBB classes into concrete ones
     * backed by {@link CmpFieldStore}. When {@code false} (default) the
     * legacy reflection-based {@code CmpAccessorInvoker} path stays in
     * effect.
     */
    public boolean isCodegenEnabled() {
        return codegenEnabled;
    }

    /**
     * Production P1.4 — directory in which generated concrete SBB
     * {@code .class} files are persisted. Defaults to
     * {@code ${java.io.tmpdir}/slee-deploy}. Ignored when
     * {@link #isCodegenEnabled()} is {@code false}.
     */
    public String getDeployDir() {
        return deployDir;
    }

    /**
     * VT-PINNING — when {@code true}, the container sets the system property
     * {@code jdk.tracePinnedThreads} to {@code "full"} at {@code start()} time,
     * enabling stack traces whenever a virtual thread is pinned to its carrier.
     * Default {@code false} (no tracing overhead). Use during development and
     * CI to catch {@code synchronized} blocks on the SBB hot path.
     */
    public boolean isOffHeapEnabled() {
        return offHeapEnabled;
    }

    public String getOffHeapStorageDir() {
        return offHeapStorageDir;
    }

    public boolean isTracePinnedThreads() {
        return tracePinnedThreads;
    }

    /**
     * Production P3 — local SBB supervision enabled. When {@code true} the
     * container starts an {@code SbbSupervisor} that force-restarts wedged
     * (delivery-timeout) or crash-looping SBB entities with exponential
     * backoff, and raises a CRITICAL alarm when restart attempts are
     * exhausted. Purely local — no cluster interaction.
     */
    public boolean isSbbSupervisionEnabled() {
        return sbbSupervisionEnabled;
    }

    /**
     * Production P3 — supervised restarts allowed per entity id before the
     * supervisor declares the entity dead (alarm + final removal). The
     * counter never resets: a session needing this many restarts in total
     * is considered too unstable to keep resurrecting.
     */
    public int getSbbRestartMaxAttempts() {
        return sbbRestartMaxAttempts;
    }

    /** Production P3 — first supervised-restart delay (exponential base). */
    public long getSbbRestartBackoffBaseMs() {
        return sbbRestartBackoffBaseMs;
    }

    /** Production P3 — ceiling for the exponential backoff delay. */
    public long getSbbRestartBackoffMaxMs() {
        return sbbRestartBackoffMaxMs;
    }

    /**
     * Production P3 (M3) — per-entity capacity of the supervised-restart
     * replay buffer. {@code 0} disables event parking entirely.
     */
    public int getSbbRestartReplayCapacity() {
        return sbbRestartReplayCapacity;
    }

    public static final class Builder {
        private int eventRouterBufferSize = DEFAULT_RING_BUFFER_SIZE;
        private int fanInQueueCapacity = DEFAULT_FAN_IN_QUEUE_CAPACITY;
        private int fanInDrainBatchSize = DEFAULT_FAN_IN_DRAIN_BATCH_SIZE;
        private boolean preferVirtualThreads = true;
        private int sbbPoolMin = DEFAULT_SBB_POOL_MIN;
        private int sbbPoolMax = DEFAULT_SBB_POOL_MAX;
        private boolean sbbPerVirtualThread = true;
        private int sbbTypePoolMinIdle = DEFAULT_SBB_TYPE_POOL_MIN_IDLE;
        private EventDeliveryMode eventDeliveryMode = EventDeliveryMode.SYNC;
        private boolean txEnabled = false;
        // Production P2.1 — cluster layer fields. Defaults match the
        // single-JVM R&D behaviour so existing embedders see no change.
        private boolean clusterEnabled = DEFAULT_CLUSTER_ENABLED;
        private String clusterStack = DEFAULT_CLUSTER_STACK;
        private String clusterInitialHosts = DEFAULT_CLUSTER_INITIAL_HOSTS;
        private String nodeId = null;
        // Production P1.4 — Javassist codegen fields. Default behaviour
        // keeps the reflective CmpAccessorInvoker path; the codegen is
        // picked up automatically when (a) enabled is left at the default
        // AND (b) the jainslee-codegen module is reachable at runtime.
        // Tests can force-disable to exercise the reflection fallback.
        private boolean codegenEnabled = true;
        private String deployDir = System.getProperty("java.io.tmpdir") + "/slee-deploy";
        private boolean tracePinnedThreads = false;
        private boolean offHeapEnabled = true;
        private String offHeapStorageDir = "";
        // Production P3 — local SBB supervision.
        private boolean sbbSupervisionEnabled = DEFAULT_SBB_SUPERVISION_ENABLED;
        private int sbbRestartMaxAttempts = DEFAULT_SBB_RESTART_MAX_ATTEMPTS;
        private long sbbRestartBackoffBaseMs = DEFAULT_SBB_RESTART_BACKOFF_BASE_MS;
        private long sbbRestartBackoffMaxMs = DEFAULT_SBB_RESTART_BACKOFF_MAX_MS;
        private int sbbRestartReplayCapacity = DEFAULT_SBB_RESTART_REPLAY_CAPACITY;

        public Builder eventRouterBufferSize(int eventRouterBufferSize) {
            if (eventRouterBufferSize <= 0 || Integer.bitCount(eventRouterBufferSize) != 1) {
                throw new IllegalArgumentException("eventRouterBufferSize must be a positive power of two");
            }
            this.eventRouterBufferSize = eventRouterBufferSize;
            return this;
        }

        /**
         * ADR 0007 D7 / P4-c — enable the RA fan-in gateway. RAs publish into a
         * bounded queue drained in batches; when the queue is full the RA is told
         * to back off, which is the signal ADR 0005's admission control needs.
         *
         * @param capacity {@code 0} disables the gateway (default, today's behaviour)
         */
        public Builder fanInQueueCapacity(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("fanInQueueCapacity must be >= 0");
            }
            this.fanInQueueCapacity = capacity;
            return this;
        }

        /** Events drained per fan-in iteration (only used when the gateway is on). */
        public Builder fanInDrainBatchSize(int batchSize) {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("fanInDrainBatchSize must be > 0");
            }
            this.fanInDrainBatchSize = batchSize;
            return this;
        }

        public Builder preferVirtualThreads(boolean preferVirtualThreads) {
            this.preferVirtualThreads = preferVirtualThreads;
            return this;
        }

        public Builder sbbPoolMin(int sbbPoolMin) {
            this.sbbPoolMin = sbbPoolMin;
            // Defer range validation until build() so callers can set min+max in any order.
            return this;
        }

        public Builder sbbPoolMax(int sbbPoolMax) {
            this.sbbPoolMax = sbbPoolMax;
            return this;
        }

        public Builder sbbPerVirtualThread(boolean sbbPerVirtualThread) {
            this.sbbPerVirtualThread = sbbPerVirtualThread;
            return this;
        }

        public Builder sbbTypePoolMinIdle(int sbbTypePoolMinIdle) {
            this.sbbTypePoolMinIdle = sbbTypePoolMinIdle;
            return this;
        }

        public Builder eventDeliveryMode(EventDeliveryMode eventDeliveryMode) {
            if (eventDeliveryMode != null) {
                this.eventDeliveryMode = eventDeliveryMode;
            }
            return this;
        }

        /**
         * Production P1.2 — enable JTA transaction wrapping for SBB event
         * delivery. Requires {@code com.microjainslee:jainslee-tx} on the
         * classpath at runtime; the container will throw
         * {@link IllegalStateException} at {@code start()} time if
         * {@code txEnabled = true} but the JTA module is missing.
         */
        public Builder txEnabled(boolean txEnabled) {
            this.txEnabled = txEnabled;
            return this;
        }

        /**
         * Production P2.1 — enable the Infinispan + JGroups cluster layer.
         * When {@code true} the container will load
         * {@code com.microjainslee.cluster.ClusterManager} reflectively at
         * {@code start()} time and call
         * {@code MicroSleeContainer.bindCluster(Object)}. Default
         * {@code false} (R&amp;D / single-JVM).
         */
        public Builder clusterEnabled(boolean clusterEnabled) {
            this.clusterEnabled = clusterEnabled;
            return this;
        }

        /**
         * Production P2.1 — JGroups transport flavour. Accepted values are
         * {@code "tcp"} (default) and {@code "udp"}. Case-insensitive. The
         * value is passed to {@code ClusterManager} as-is.
         */
        public Builder clusterStack(String clusterStack) {
            if (clusterStack != null) {
                this.clusterStack = clusterStack;
            }
            return this;
        }

        /**
         * Production P2.1 — comma-separated JGroups discovery initial hosts,
         * e.g. {@code "host1[7800],host2[7800]"}. Default
         * {@code "localhost[7800]"}.
         */
        public Builder clusterInitialHosts(String clusterInitialHosts) {
            if (clusterInitialHosts != null && !clusterInitialHosts.isBlank()) {
                this.clusterInitialHosts = clusterInitialHosts;
            }
            return this;
        }

        /**
         * Production P2.1 — stable node id for this JVM. When {@code null}
         * (default) the {@code ClusterManager} generates a short random
         * UUID at construction time. Production embedders should set this
         * to a stable value (hostname, K8s pod name) so log lines and
         * JGroups views are traceable across restarts.
         */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /**
         * Production P1.4 — toggle the Javassist CMP codegen path. Default
         * {@code true}. When {@code false} the kernel falls back to the
         * reflection-based {@code CmpAccessorInvoker} flow even if the
         * codegen module is on the runtime classpath.
         */
        public Builder codegenEnabled(boolean codegenEnabled) {
            this.codegenEnabled = codegenEnabled;
            return this;
        }

        /**
         * Production P1.4 — directory in which generated concrete SBB
         * {@code .class} files are persisted. Default
         * {@code ${java.io.tmpdir}/slee-deploy}. The directory is created
         * on demand by the codegen helper.
         */
        public Builder deployDir(String deployDir) {
            if (deployDir != null && !deployDir.isBlank()) {
                this.deployDir = deployDir;
            }
            return this;
        }

        /**
         * VT-PINNING — enable {@code -Djdk.tracePinnedThreads=full} at
         * container start. When {@code true}, the JVM emits a stack trace
         * every time a virtual thread is pinned to its carrier thread
         * (typically via {@code synchronized} blocks). Default {@code false}.
         * Use during development and CI to audit pinning-free code paths.
         */
        /** Honor {@code @OffHeap} annotations (default true). */
        public Builder offHeapEnabled(boolean enable) {
            this.offHeapEnabled = enable;
            return this;
        }

        /** Directory for MMAP arenas when {@code @OffHeap.filePath} is empty. */
        public Builder offHeapStorageDir(String dir) {
            this.offHeapStorageDir = dir == null ? "" : dir;
            return this;
        }

        public Builder tracePinnedThreads(boolean enable) {
            this.tracePinnedThreads = enable;
            return this;
        }

        /** Production P3 — toggle local SBB supervision. Default {@code true}. */
        public Builder sbbSupervisionEnabled(boolean enable) {
            this.sbbSupervisionEnabled = enable;
            return this;
        }

        /** Production P3 — supervised restarts per entity id before DEAD. Default 3. */
        public Builder sbbRestartMaxAttempts(int maxAttempts) {
            this.sbbRestartMaxAttempts = maxAttempts;
            return this;
        }

        /** Production P3 — exponential backoff base delay in ms. Default 100. */
        public Builder sbbRestartBackoffBaseMs(long baseMs) {
            this.sbbRestartBackoffBaseMs = baseMs;
            return this;
        }

        /** Production P3 — exponential backoff ceiling in ms. Default 3200. */
        public Builder sbbRestartBackoffMaxMs(long maxMs) {
            this.sbbRestartBackoffMaxMs = maxMs;
            return this;
        }

        /**
         * Production P3 (M3) — parked-event bound per entity for the
         * supervised-restart replay buffer. {@code 0} disables parking.
         * Default 256.
         */
        public Builder sbbRestartReplayCapacity(int capacity) {
            this.sbbRestartReplayCapacity = capacity;
            return this;
        }

        public MicroSleeConfiguration build() {
            if (sbbPoolMin < 0) {
                throw new IllegalArgumentException("sbbPoolMin must be >= 0 (was " + sbbPoolMin + ")");
            }
            if (sbbPoolMax < 1) {
                throw new IllegalArgumentException("sbbPoolMax must be >= 1 (was " + sbbPoolMax + ")");
            }
            if (sbbPoolMin > sbbPoolMax) {
                throw new IllegalArgumentException(
                        "sbbPoolMin (" + sbbPoolMin + ") must be <= sbbPoolMax (" + sbbPoolMax + ")");
            }
            if (sbbTypePoolMinIdle < 0) {
                throw new IllegalArgumentException(
                        "sbbTypePoolMinIdle must be >= 0 (was " + sbbTypePoolMinIdle + ")");
            }
            if (sbbRestartMaxAttempts < 1) {
                throw new IllegalArgumentException(
                        "sbbRestartMaxAttempts must be >= 1 (was " + sbbRestartMaxAttempts + ")");
            }
            if (sbbRestartBackoffBaseMs < 0L) {
                throw new IllegalArgumentException(
                        "sbbRestartBackoffBaseMs must be >= 0 (was " + sbbRestartBackoffBaseMs + ")");
            }
            if (sbbRestartBackoffMaxMs < sbbRestartBackoffBaseMs) {
                throw new IllegalArgumentException(
                        "sbbRestartBackoffMaxMs (" + sbbRestartBackoffMaxMs
                                + ") must be >= sbbRestartBackoffBaseMs ("
                                + sbbRestartBackoffBaseMs + ")");
            }
            if (sbbRestartReplayCapacity < 0) {
                throw new IllegalArgumentException(
                        "sbbRestartReplayCapacity must be >= 0 (was "
                                + sbbRestartReplayCapacity + ")");
            }
            return new MicroSleeConfiguration(this);
        }
    }
}
