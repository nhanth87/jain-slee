/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import com.microjainslee.core.MicroSleeConfiguration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ADR 0007 D12 — the third vote of a two-node active/active pair.
 *
 * <p>
 * Two SS7 nodes cannot be both safe and available when the link between them
 * breaks: with one member each, neither side is a majority, so the fenced lease
 * caches deny both (safe, but nobody can take a dialog over). A witness is a
 * small JVM that joins the same JGroups cluster and <b>defines the same fenced
 * caches</b> — that is what makes it count — but runs no RA, holds no socket and
 * owns no dialog. Whichever SS7 node still sees the witness keeps the majority
 * and may take dialogs over; the other one stops transmitting.
 *
 * <p>
 * Run it on a third host (a failure domain of its own, not on either SS7 node):
 * <pre>
 * java -Dmicrojainslee.container.cluster-node-id=witness-1 \
 *      -Dmicrojainslee.container.cluster-initial-hosts=ss7-1[7800],ss7-2[7800],witness-1[7800] \
 *      -Dmicrojainslee.witness.ra-names=ra-jss7 \
 *      -cp ... com.microjainslee.cluster.ClusterWitness
 * </pre>
 * The RA names must match the RAs whose leases it votes on (default
 * {@code ra-jss7}).
 */
public final class ClusterWitness implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(ClusterWitness.class);

    public static final String PROP_NODE_ID = "microjainslee.container.cluster-node-id";
    public static final String PROP_STACK = "microjainslee.container.cluster-stack";
    public static final String PROP_INITIAL_HOSTS = "microjainslee.container.cluster-initial-hosts";
    public static final String PROP_RA_NAMES = "microjainslee.witness.ra-names";

    private final ClusterManager clusterManager;
    private final List<RaDialogLeaseCaches> leases;

    private ClusterWitness(ClusterManager clusterManager, List<RaDialogLeaseCaches> leases) {
        this.clusterManager = clusterManager;
        this.leases = leases;
    }

    /**
     * Join the cluster and define the fenced lease cache of every named RA.
     * Starts {@code clusterManager} if it is not started yet.
     */
    public static ClusterWitness start(ClusterManager clusterManager, Collection<String> raNames) {
        Objects.requireNonNull(clusterManager, "clusterManager");
        clusterManager.start();
        List<RaDialogLeaseCaches> defined = new ArrayList<>();
        for (String raName : raNames) {
            if (raName != null && !raName.isBlank()) {
                // No timers: the witness never owns a lease, it only votes.
                defined.add(RaDialogLeaseCaches.createUnscheduled(clusterManager, raName.trim(),
                        RaDialogLeaseCaches.DEFAULT_LEASE_TTL_MS));
            }
        }
        LOG.info("Cluster witness up node={} voting on {} (members={})", clusterManager.getNodeId(), raNames,
                clusterManager.getCacheManager().getMembers());
        return new ClusterWitness(clusterManager, defined);
    }

    public ClusterManager clusterManager() {
        return clusterManager;
    }

    /** Number of lease caches this witness votes on. */
    public int votedCaches() {
        return leases.size();
    }

    @Override
    public void close() {
        clusterManager.stop();
    }

    public static void main(String[] args) throws InterruptedException {
        String nodeId = System.getProperty(PROP_NODE_ID, "witness-1");
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .nodeId(nodeId)
                .clusterStack(System.getProperty(PROP_STACK, "tcp"))
                .clusterInitialHosts(System.getProperty(PROP_INITIAL_HOSTS, ""))
                .build();
        List<String> raNames = Arrays.asList(System.getProperty(PROP_RA_NAMES, "ra-jss7").split(","));
        ClusterWitness witness = start(new ClusterManager(cfg, nodeId), raNames);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            witness.close();
            stopped.countDown();
        }, "witness-shutdown"));
        stopped.await();
    }
}
