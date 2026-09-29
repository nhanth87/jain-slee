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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.partitionhandling.AvailabilityException;

/**
 * ADR 0007 D3 / D12 — leased dialog ownership and the transmit fence.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li><b>Claim before send.</b> A node claims the activity id
 *       ({@code putIfAbsent}) <em>before</em> the dialog-creating command goes on
 *       the wire. A client retry of the same activity id on another node loses
 *       the claim and is refused — one MAP transaction, not two.</li>
 *   <li><b>Heartbeat.</b> The owner renews its leases on a timer, so a TCAP
 *       dialog that is quiet for a minute is not mistaken for a dead owner.</li>
 *   <li><b>Takeover is lazy.</b> Nobody reassigns leases in the background. A
 *       node takes a dialog over only when traffic for it actually arrives and
 *       it can import the dialog ({@link #takeOver}); the lease then moves with
 *       {@code generation + 1}. A background reaper that hands leases to
 *       whichever node sweeps first points ownership at a node that does not
 *       hold the TCAP dialog.</li>
 *   <li><b>Takeover needs the owner gone from the view</b>, not an expired
 *       timestamp: expiry is written with the owner's clock and compared with
 *       ours, so it would make ownership depend on clock skew.</li>
 *   <li><b>Transmit fence.</b> {@link #mayTransmit} is checked before every
 *       send on an existing dialog: still ours, same boot epoch, not expired by
 *       our own clock. The cache is fenced ({@link ClusterManager#getFencedCache}):
 *       on the minority side of a partition every read throws
 *       {@link AvailabilityException}, which the fence turns into "do not send".
 *       That is what stops a partitioned-but-alive zombie.</li>
 *   <li><b>Reaper = garbage collection only.</b> It deletes leases whose owner
 *       left the view and that expired a full TTL ago — dialogs nobody will
 *       continue.</li>
 * </ul>
 *
 * <p>
 * The lease cache is REPL_SYNC: every check is a local read, so the fence costs
 * one hash lookup per transmit, not a network round trip.
 */
public final class RaDialogLeaseCaches {

    private static final Logger LOG = LogManager.getLogger(RaDialogLeaseCaches.class);

    /** Lease cache name, one per RA so unrelated protocols cannot collide. */
    public static String cacheNameFor(String raName) {
        return "ra-" + raName + "-lease";
    }

    /** Default lease TTL. Must exceed the heartbeat period with margin. */
    public static final long DEFAULT_LEASE_TTL_MS = 60_000L;

    /** Default garbage-collection sweep period. */
    public static final long DEFAULT_SWEEP_PERIOD_MS = 15_000L;

    private final ClusterManager clusterManager;
    private final String raName;
    private final String localNodeId;
    private final long bootEpochMs;
    private final long leaseTtlMs;
    private final Cache<String, RaDialogLease> cache;
    private final AtomicLong claimsRefused = new AtomicLong();
    private final AtomicLong takeoversOk = new AtomicLong();
    private final AtomicLong takeoversRefused = new AtomicLong();
    private final AtomicLong fenceBlocked = new AtomicLong();
    private final AtomicLong orphansCollected = new AtomicLong();
    private final AtomicLong staleReleased = new AtomicLong();
    private volatile ScheduledExecutorService scheduler;
    private volatile java.util.function.Predicate<String> localLiveness;

    private RaDialogLeaseCaches(ClusterManager clusterManager, String raName, String localNodeId,
                                long bootEpochMs, long leaseTtlMs, Cache<String, RaDialogLease> cache) {
        this.clusterManager = clusterManager;
        this.raName = raName;
        this.localNodeId = localNodeId;
        this.bootEpochMs = bootEpochMs;
        this.leaseTtlMs = leaseTtlMs;
        this.cache = cache;
    }

    public static RaDialogLeaseCaches create(ClusterManager cm, String raName) {
        return create(cm, raName, DEFAULT_LEASE_TTL_MS);
    }

    public static RaDialogLeaseCaches create(ClusterManager cm, String raName, long leaseTtlMs) {
        RaDialogLeaseCaches caches = createUnscheduled(cm, raName, leaseTtlMs);
        caches.startTimers();
        return caches;
    }

    /** Same as {@link #create} without the heartbeat / GC timers — tests drive them by hand. */
    static RaDialogLeaseCaches createUnscheduled(ClusterManager cm, String raName, long leaseTtlMs) {
        if (cm == null) {
            throw new IllegalArgumentException("clusterManager is required");
        }
        String nodeId = cm.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = "local-" + raName;
        }
        Cache<String, RaDialogLease> cache =
                cm.getFencedCache(cacheNameFor(raName), CacheMode.REPL_SYNC, new RaDialogLeaseMergePolicy());
        return new RaDialogLeaseCaches(cm, raName, nodeId, System.currentTimeMillis(), leaseTtlMs, cache);
    }

    private void startTimers() {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ra-lease-" + raName);
            t.setDaemon(true);
            return t;
        });
        long heartbeatMs = Math.max(1_000L, leaseTtlMs / 3);
        ex.scheduleWithFixedDelay(() -> safely("heartbeat", () -> heartbeat()),
                heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        ex.scheduleWithFixedDelay(() -> safely("orphan sweep", () -> collectOrphans()),
                DEFAULT_SWEEP_PERIOD_MS, DEFAULT_SWEEP_PERIOD_MS, TimeUnit.MILLISECONDS);
        this.scheduler = ex;
    }

    public void stop() {
        ScheduledExecutorService ex = this.scheduler;
        if (ex != null) {
            ex.shutdownNow();
            this.scheduler = null;
        }
    }

    public String localNodeId() {
        return localNodeId;
    }

    /**
     * Tell the heartbeat which of our leases still protect something alive on
     * this node. Without it the heartbeat renews every lease we ever claimed, so
     * one missed {@link #release} (dialog ended through a path that did not
     * release, send failed after the claim) is renewed forever and never
     * collected — collection only looks at leases of absent owners.
     */
    public void setLocalLiveness(java.util.function.Predicate<String> alive) {
        this.localLiveness = alive;
    }

    public long bootEpochMs() {
        return bootEpochMs;
    }

    public long leaseTtlMs() {
        return leaseTtlMs;
    }

    /**
     * Claim a brand-new activity. First writer wins.
     *
     * @return {@code false} when another incarnation holds it, or when this node
     *         is on the minority side of a partition
     */
    public boolean tryClaim(String activityId) {
        try {
            RaDialogLease mine = RaDialogLease.claim(activityId, localNodeId, raName,
                    bootEpochMs, System.currentTimeMillis(), leaseTtlMs);
            RaDialogLease prior = cache.putIfAbsent(activityId, mine);
            if (prior == null || prior.stillOwnedBy(localNodeId, bootEpochMs)) {
                return true;
            }
            if (localNodeId.equals(prior.ownerNodeId()) && prior.ownerBootEpochMs() < bootEpochMs) {
                // Our own previous incarnation (restart): its dialogs died with it and
                // this node now reuses its OTID range. Supersede with generation + 1.
                RaDialogLease next = prior.reclaimedBy(localNodeId, raName, bootEpochMs,
                        System.currentTimeMillis(), leaseTtlMs);
                if (cache.replace(activityId, prior, next)) {
                    return true;
                }
            }
            claimsRefused.incrementAndGet();
            return false;
        } catch (AvailabilityException e) {
            claimsRefused.incrementAndGet();
            LOG.warn("[{}] claim of {} refused: lease cache unavailable (partition minority?)", raName, activityId);
            return false;
        }
    }

    /**
     * The transmit fence. Call immediately before writing to the wire for an
     * existing dialog.
     *
     * <p>
     * An activity with no lease at all is claimed now (a dialog opened before
     * the lease layer was wired, or an inbound BEGIN); that claim is the same
     * first-writer-wins check.
     *
     * @return {@code true} only when this incarnation owns an unexpired lease and
     *         the cache is available
     */
    public boolean mayTransmit(String activityId) {
        try {
            RaDialogLease lease = cache.get(activityId);
            if (lease == null) {
                if (tryClaim(activityId)) {
                    return true;
                }
                fenceBlocked.incrementAndGet();
                return false;
            }
            if (lease.stillOwnedBy(localNodeId, bootEpochMs) && !lease.isExpired(System.currentTimeMillis())) {
                return true;
            }
            fenceBlocked.incrementAndGet();
            LOG.warn("[{}] FENCE: not transmitting on {} — lease is {}", raName, activityId, lease);
            return false;
        } catch (AvailabilityException e) {
            fenceBlocked.incrementAndGet();
            LOG.warn("[{}] FENCE: not transmitting on {} — lease cache unavailable (partition minority)",
                    raName, activityId);
            return false;
        }
    }

    /**
     * Take an activity over because its traffic arrived here and its owner is
     * gone. Succeeds when the lease is ours already, absent, or held by a node
     * that is not in the view; the CAS on the exact prior value makes two
     * survivors racing unable to both win.
     */
    public boolean takeOver(String activityId) {
        try {
            RaDialogLease lease = cache.get(activityId);
            long now = System.currentTimeMillis();
            if (lease == null) {
                boolean claimed = cache.putIfAbsent(activityId,
                        RaDialogLease.claim(activityId, localNodeId, raName, bootEpochMs, now, leaseTtlMs)) == null;
                return count(claimed, activityId, null);
            }
            if (lease.stillOwnedBy(localNodeId, bootEpochMs)) {
                return true;
            }
            if (clusterManager.isNodePresent(lease.ownerNodeId())) {
                return count(false, activityId, lease);
            }
            RaDialogLease next = lease.reclaimedBy(localNodeId, raName, bootEpochMs, now, leaseTtlMs);
            boolean won = cache.replace(activityId, lease, next);
            if (won) {
                LOG.info("[{}] took over activity={} from absent node={} (gen {} -> {})",
                        raName, activityId, lease.ownerNodeId(), lease.generation(), next.generation());
            }
            return count(won, activityId, lease);
        } catch (AvailabilityException e) {
            takeoversRefused.incrementAndGet();
            LOG.warn("[{}] takeover of {} refused: lease cache unavailable (partition minority)", raName, activityId);
            return false;
        }
    }

    private boolean count(boolean ok, String activityId, RaDialogLease was) {
        if (ok) {
            takeoversOk.incrementAndGet();
        } else {
            takeoversRefused.incrementAndGet();
            LOG.info("[{}] takeover of {} refused (lease {})", raName, activityId, was);
        }
        return ok;
    }

    /** Extend our own lease. A no-op when we no longer own it. */
    public boolean renew(String activityId) {
        try {
            RaDialogLease current = cache.get(activityId);
            if (current == null || !current.stillOwnedBy(localNodeId, bootEpochMs)) {
                return false;
            }
            return cache.replace(activityId, current, current.renewed(System.currentTimeMillis(), leaseTtlMs));
        } catch (AvailabilityException e) {
            return false;
        }
    }

    /** @return the current lease, or {@code null} (also when unavailable). */
    public RaDialogLease lookup(String activityId) {
        if (activityId == null) {
            return null;
        }
        try {
            return cache.get(activityId);
        } catch (AvailabilityException e) {
            return null;
        }
    }

    public boolean isLocalOwner(String activityId) {
        RaDialogLease lease = lookup(activityId);
        return lease != null && lease.stillOwnedBy(localNodeId, bootEpochMs)
                && !lease.isExpired(System.currentTimeMillis());
    }

    /** Dialog ended: drop our lease (only if it is still ours). */
    public void release(String activityId) {
        try {
            RaDialogLease current = cache.get(activityId);
            if (current != null && current.stillOwnedBy(localNodeId, bootEpochMs)) {
                cache.remove(activityId, current);
            }
        } catch (AvailabilityException e) {
            LOG.debug("[{}] release of {} deferred: lease cache unavailable", raName, activityId);
        }
    }

    /** @return every lease this node currently owns, for heartbeat sweeps. */
    public List<RaDialogLease> localLeases() {
        List<RaDialogLease> out = new ArrayList<>();
        for (Map.Entry<String, RaDialogLease> e : cache.entrySet()) {
            RaDialogLease lease = e.getValue();
            if (lease != null && lease.stillOwnedBy(localNodeId, bootEpochMs)) {
                out.add(lease);
            }
        }
        return out;
    }

    /**
     * Renew leases for every dialog we own that is past half its TTL, so a quiet
     * dialog is not mistaken for a dead owner.
     *
     * @return leases renewed by this call
     */
    public int heartbeat() {
        int renewed = 0;
        long now = System.currentTimeMillis();
        java.util.function.Predicate<String> alive = this.localLiveness;
        for (RaDialogLease lease : localLeases()) {
            if (lease.leaseExpiresAtEpochMs() - now > leaseTtlMs / 2) {
                continue;
            }
            if (alive != null && !alive.test(lease.activityId())) {
                if (cache.remove(lease.activityId(), lease)) {
                    staleReleased.incrementAndGet();
                }
                continue;
            }
            if (cache.replace(lease.activityId(), lease, lease.renewed(now, leaseTtlMs))) {
                renewed++;
            }
        }
        return renewed;
    }

    /**
     * Delete leases whose owner left the view and that expired at least one TTL
     * ago. Never assigns ownership.
     *
     * @return ids removed by this call
     */
    public List<String> collectOrphans() {
        long now = System.currentTimeMillis();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, RaDialogLease> e : cache.entrySet()) {
            RaDialogLease lease = e.getValue();
            if (lease == null || lease.stillOwnedBy(localNodeId, bootEpochMs)) {
                continue;
            }
            if (now < lease.leaseExpiresAtEpochMs() + leaseTtlMs) {
                continue;
            }
            if (clusterManager.isNodePresent(lease.ownerNodeId())) {
                continue;
            }
            if (cache.remove(e.getKey(), lease)) {
                removed.add(e.getKey());
                orphansCollected.incrementAndGet();
            }
        }
        if (!removed.isEmpty()) {
            LOG.info("[{}] collected {} orphaned dialog leases", raName, removed.size());
        }
        return removed;
    }

    private void safely(String what, Runnable task) {
        try {
            task.run();
        } catch (AvailabilityException e) {
            LOG.debug("[{}] {} skipped: lease cache unavailable", raName, what);
        } catch (RuntimeException e) {
            LOG.warn("[{}] {} failed", raName, what, e);
        }
    }

    /** Claims refused because another incarnation held the activity. */
    public long claimsRefused() {
        return claimsRefused.get();
    }

    public long takeoversOk() {
        return takeoversOk.get();
    }

    public long takeoversRefused() {
        return takeoversRefused.get();
    }

    /** Transmits blocked by the fence — non-zero on a zombie / partition minority. */
    public long fenceBlocked() {
        return fenceBlocked.get();
    }

    public long orphansCollected() {
        return orphansCollected.get();
    }

    /** Own leases dropped by the heartbeat because nothing local used them any more. */
    public long staleReleased() {
        return staleReleased.get();
    }

    @Override
    public String toString() {
        return "RaDialogLeaseCaches[ra=" + raName + ", node=" + localNodeId
                + ", bootEpochMs=" + bootEpochMs + ", ttlMs=" + leaseTtlMs + "]";
    }

    /** Total lease records currently in the cache (owned by us and others). */
    public int leaseCount() {
        return cache.size();
    }

    /** Number of leases in the cache owned by another node. */
    public int remoteLeaseCount() {
        int n = 0;
        for (RaDialogLease lease : cache.values()) {
            if (lease != null && !lease.stillOwnedBy(localNodeId, bootEpochMs)) {
                n++;
            }
        }
        return n;
    }
}
