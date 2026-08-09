/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.SctpEndpointLease;
import com.microjainslee.cluster.Ss7DialogClusterCaches;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * n-n SCTP endpoint fence: each RA claims one {@code ip:port}; on Infinispan
 * view change, survivors CAS-claim leases owned by departed nodes (VIP path).
 *
 * <p>Uses ISPN lease/CAS + generation only — no ZooKeeper.
 */
@Listener
public final class SctpEndpointFailoverCoordinator {

    private static final Logger LOG = LogManager.getLogger(SctpEndpointFailoverCoordinator.class);

    private final String localNodeId;
    private final List<String> clusterEndpoints;
    private final String preferredEndpoint;
    private final Ss7DialogClusterCaches caches;
    private final ClusterManager clusterManager;
    private final SctpEndpointBinder binder;
    private volatile boolean started;

    public SctpEndpointFailoverCoordinator(
            String localNodeId,
            List<String> clusterEndpoints,
            String preferredEndpoint,
            Ss7DialogClusterCaches caches,
            ClusterManager clusterManager,
            SctpEndpointBinder binder) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.clusterEndpoints = List.copyOf(Objects.requireNonNull(clusterEndpoints, "clusterEndpoints"));
        this.preferredEndpoint = Objects.requireNonNull(preferredEndpoint, "preferredEndpoint");
        this.caches = Objects.requireNonNull(caches, "caches");
        this.clusterManager = Objects.requireNonNull(clusterManager, "clusterManager");
        this.binder = Objects.requireNonNull(binder, "binder");
        if (!this.clusterEndpoints.contains(preferredEndpoint)) {
            throw new IllegalArgumentException(
                    "preferredEndpoint=" + preferredEndpoint + " not in clusterEndpoints="
                            + this.clusterEndpoints);
        }
    }

    public void start() {
        if (started) {
            return;
        }
        claimPreferred();
        clusterManager.getCacheManager().addListener(this);
        started = true;
        LOG.info("[ra-jss7] SCTP endpoint coordinator started node={} preferred={} endpoints={}",
                localNodeId, preferredEndpoint, clusterEndpoints);
    }

    public void stop() {
        if (!started) {
            return;
        }
        try {
            clusterManager.getCacheManager().removeListener(this);
        } catch (RuntimeException e) {
            LOG.debug("[ra-jss7] remove endpoint view listener: {}", e.toString());
        }
        // Release leases we still own (best-effort).
        for (String ep : clusterEndpoints) {
            SctpEndpointLease lease = caches.getEndpointLease(ep);
            if (lease != null && localNodeId.equals(lease.ownerNodeId())) {
                caches.removeEndpointLease(ep);
            }
        }
        started = false;
    }

    public String preferredEndpoint() {
        return preferredEndpoint;
    }

    @ViewChanged
    public void onViewChanged(ViewChangedEvent event) {
        if (event == null) {
            return;
        }
        LOG.info("[ra-jss7] ISPN view changed — scanning SCTP endpoint leases for orphans");
        reclaimOrphanedEndpoints();
    }

    /** Preferred endpoint: putIfAbsent or refresh if we already own it. */
    public boolean claimPreferred() {
        long now = System.currentTimeMillis();
        SctpEndpointLease existing = caches.getEndpointLease(preferredEndpoint);
        if (existing == null) {
            SctpEndpointLease lease = new SctpEndpointLease(preferredEndpoint, localNodeId, 0L, now);
            if (caches.tryPutEndpointLeaseIfAbsent(lease)) {
                binder.onEndpointClaimed(preferredEndpoint, 0L, false);
                return true;
            }
            existing = caches.getEndpointLease(preferredEndpoint);
        }
        if (existing != null && localNodeId.equals(existing.ownerNodeId())) {
            caches.putEndpointLease(existing.withOwner(localNodeId, existing.generation(), now));
            binder.onEndpointClaimed(preferredEndpoint, existing.generation(), false);
            return true;
        }
        LOG.warn("[ra-jss7] preferred SCTP endpoint {} owned by {}; not claiming",
                preferredEndpoint, existing == null ? "?" : existing.ownerNodeId());
        return false;
    }

    /**
     * Claim endpoints whose owner left the cluster (or lease owner not in view).
     *
     * @return endpoints successfully claimed this pass
     */
    public List<String> reclaimOrphanedEndpoints() {
        List<String> claimed = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String ep : clusterEndpoints) {
            if (ep.equals(preferredEndpoint)) {
                continue; // preferred handled at start / refresh
            }
            SctpEndpointLease lease = caches.getEndpointLease(ep);
            if (lease == null) {
                // Unclaimed spare — only take over if we already hold preferred
                // and are the only remaining node for that endpoint (optional).
                // Steady-state: leave null until a node with that preferred index joins.
                continue;
            }
            if (localNodeId.equals(lease.ownerNodeId())) {
                continue;
            }
            if (clusterManager.isNodePresent(lease.ownerNodeId())) {
                continue;
            }
            if (caches.tryClaimEndpointLease(lease, localNodeId, now)) {
                SctpEndpointLease after = caches.getEndpointLease(ep);
                long gen = after == null ? lease.generation() + 1 : after.generation();
                LOG.info("[ra-jss7] claimed orphaned SCTP endpoint {} (was owner={}) generation={}",
                        ep, lease.ownerNodeId(), gen);
                binder.onEndpointClaimed(ep, gen, true);
                claimed.add(ep);
            }
        }
        return claimed;
    }
}
