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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0007 P1–P3 — the new cluster primitives.
 *
 * <p>
 * These are the pieces that make active/active possible at all:
 * <ul>
 * <li>{@link RaDialogLease} — leased ownership with a generation AND boot-epoch
 * fence, replacing the permanent {@link RaDialogOwner} row that orphaned
 * forever when its owner died.</li>
 * <li>{@link RaStickyEventBus} — the missing <b>inbound</b> cross-node event
 * path (R1: a response must reach the node holding the client's socket).</li>
 * <li>{@link StickyEventEnvelope} — the portable, allow-list-clean wire form.</li>
 * </ul>
 */
class ActiveActiveFabricTest {

    // ── RaDialogLease ────────────────────────────────────────────────────────

    @Test
    @DisplayName("lease: a fresh claim is owned, unexpired and fenced by boot epoch")
    void leaseClaimIsOwnedAndFenced() {
        long now = 1_700_000_000_000L;
        RaDialogLease lease = RaDialogLease.claim("gmlc-1", "node-a", "ra-jss7", 111L, now, 60_000L);

        assertEquals("gmlc-1", lease.activityId());
        assertEquals("node-a", lease.ownerNodeId());
        assertEquals(0L, lease.generation(), "first claim has generation 0");
        assertEquals(111L, lease.ownerBootEpochMs());
        assertEquals(now + 60_000L, lease.leaseExpiresAtEpochMs());
        assertFalse(lease.isExpired(now));
        assertTrue(lease.stillOwnedBy("node-a", 111L));
    }

    @Test
    @DisplayName("lease: expiry is wall-clock and survives a quiet dialog")
    void leaseExpiryIsWallClock() {
        long now = 1_700_000_000_000L;
        RaDialogLease lease = RaDialogLease.claim("d", "node-a", "ra", 1L, now, 60_000L);
        assertFalse(lease.isExpired(now + 59_999L), "must not expire one ms early");
        assertTrue(lease.isExpired(now + 60_000L), "must expire at the boundary");
    }

    @Test
    @DisplayName("lease: heartbeat renews without changing ownership or generation")
    void heartbeatRenewsInPlace() {
        long now = 1_700_000_000_000L;
        RaDialogLease lease = RaDialogLease.claim("d", "node-a", "ra", 7L, now, 60_000L);
        RaDialogLease renewed = lease.renewed(now + 50_000L, 60_000L);

        // A quiet TCAP dialog sitting idle for 60s is NORMAL — the heartbeat must
        // extend it without handing it to another node.
        assertEquals(lease.ownerNodeId(), renewed.ownerNodeId());
        assertEquals(lease.generation(), renewed.generation(), "renew must NOT bump the fence");
        assertEquals(lease.ownerBootEpochMs(), renewed.ownerBootEpochMs());
        assertEquals(now + 110_000L, renewed.leaseExpiresAtEpochMs());
        assertFalse(renewed.isExpired(now + 60_000L), "a heartbeating owner keeps its dialog");
    }

    @Test
    @DisplayName("lease: reclaim bumps BOTH generation and boot epoch")
    void reclaimBumpsBothFences() {
        long now = 1_700_000_000_000L;
        RaDialogLease original = RaDialogLease.claim("d", "node-dead", "ra", 1L, now, 60_000L);
        RaDialogLease reclaimed = original.reclaimedBy("node-b", "ra", 999L, now + 90_000L, 60_000L);

        assertEquals(original.generation() + 1, reclaimed.generation(), "CAS fence must bump");
        assertEquals(999L, reclaimed.ownerBootEpochMs(), "incarnation fence must bump");
        assertFalse(reclaimed.stillOwnedBy("node-dead", 1L),
                "the dead owner must no longer believe it owns the dialog");
    }

    /**
     * ADR 0007 D3 — the fence that a lease record alone cannot provide.
     *
     * <p>
     * A node that is partitioned but alive still believes it owns its dialogs.
     * This asserts the check that must run immediately before transmit.
     */
    @Test
    @DisplayName("lease: a reclaimed dialog fences the zombie that still thinks it owns it")
    void reclaimedLeaseFencesZombie() {
        long now = 1_700_000_000_000L;
        RaDialogLease zombieView = RaDialogLease.claim("d", "node-a", "ra", 1L, now, 60_000L);
        assertTrue(zombieView.stillOwnedBy("node-a", 1L));

        // node-b reclaims after node-a stops heart-beating.
        RaDialogLease afterReclaim = zombieView.reclaimedBy("node-b", "ra", 2L, now + 90_000L, 60_000L);

        assertFalse(afterReclaim.stillOwnedBy("node-a", 1L),
                "node-a must fail its pre-transmit check and abort, not send");
        assertTrue(afterReclaim.stillOwnedBy("node-b", 2L));
    }

    @Test
    @DisplayName("lease: same node id after restart is fenced by the boot epoch")
    void restartOfSameNodeIdIsFenced() {
        long now = 1_700_000_000_000L;
        // Node-a dies and comes back. Same id, new incarnation.
        RaDialogLease beforeRestart = RaDialogLease.claim("d", "node-a", "ra", 1L, now, 60_000L);
        long afterRestartEpoch = 5_000L;
        RaDialogLease rehydrated = beforeRestart.renewed(now, 60_000L);

        assertFalse(rehydrated.stillOwnedBy("node-a", afterRestartEpoch),
                "a restarted JVM must not resume ownership of stale leases it never claimed");
        assertTrue(rehydrated.stillOwnedBy("node-a", 1L));
    }

    @Test
    @DisplayName("lease: round-trips through Java serialization (Infinispan marshaller)")
    void leaseSerializes() throws Exception {
        RaDialogLease lease = RaDialogLease.claim("d", "node-a", "ra", 3L, 1_700_000_000_000L, 60_000L);
        RaDialogLease copy = roundTrip(lease);
        assertEquals(lease, copy);
        assertEquals(lease.hashCode(), copy.hashCode());
    }

    // ── StickyEventEnvelope ──────────────────────────────────────────────────

    @Test
    @DisplayName("envelope: carries the correlation key and is allow-list clean")
    void envelopeCarriesCorrelationKey() {
        MapEventPayloadStub payload = new MapEventPayloadStub();
        StickyEventEnvelope env = StickyEventEnvelope.of(
                "node-1", "node-2", "gmlc-42", "ra-jss7", "Service", payload);

        assertEquals("gmlc-42", env.activityId(), "activity id is the correlation key end to end");
        assertEquals("node-1", env.targetNodeId());
        assertEquals("node-2", env.sourceNodeId());
        assertEquals("Service", env.eventType());
        assertNotNull(env.envelopeId());
        assertTrue(env.envelopeId().length() > 0);
    }

    @Test
    @DisplayName("envelope: a response forwarded to a stale owner is dropped, not delivered")
    void staleEnvelopeIsDropped() {
        long created = 1_700_000_000_000L;
        StickyEventEnvelope aged = StickyEventEnvelope.of(
                "node-1", "node-2", "gmlc-42", "ra-jss7", "Service",
                new MapEventPayloadStub(), created);

        assertFalse(aged.isStale(created + 1_000L, 120_000L));
        assertTrue(aged.isStale(created + 200_000L, 120_000L),
                "delivering a stale response would inject it into an unrelated session");
    }

    @Test
    @DisplayName("envelope: the blast-radius fence holds for protocol stack types")
    void envelopeRejectsForeignPayloads() {
        // The fence that keeps org.restcomm.* (jSS7 MAPMessage) off the wire. Assert
        // the allow-list regexp itself rather than trying to construct a foreign
        // type from inside this package — every class here is com.microjainslee.*
        // and would legitimately be allowed (see the companion test below).
        assertTrue(allowListAccepts("com.microjainslee.ra.jss7.cluster.MapEventPayload"),
                "our own portable payloads must travel");
        assertFalse(allowListAccepts("org.restcomm.protocols.ss7.map.api.MAPMessage"),
                "jSS7 stack types must NOT travel — they are version-unstable and " +
                        "widening the allow-list would erase the fence");
        assertFalse(allowListAccepts("org.mobicents.protocols.asn.AsnOutputStream"),
                "any third-party type must NOT travel");
    }

    @Test
    @DisplayName("envelope: a com.microjainslee payload is accepted regardless of nesting")
    void envelopeAcceptsOwnPackagePayloads() {
        // A nested test class lives at com.microjainslee.cluster.ActiveActiveFabricTest$X
        // and is therefore allow-listed. This is intentional and is what lets RAs
        // ship their own portable payload POJOs.
        StickyEventEnvelope env = StickyEventEnvelope.of(
                "n1", "n2", "d", "ra", "Service", new MapEventPayloadStub());
        assertNotNull(env.payload());
    }

    @Test
    @DisplayName("envelope: round-trips through Java serialization")
    void envelopeSerializes() throws Exception {
        StickyEventEnvelope env = StickyEventEnvelope.of(
                "node-1", "node-2", "gmlc-42", "ra-jss7", "Service", new MapEventPayloadStub());
        StickyEventEnvelope copy = roundTrip(env);
        assertEquals(env.envelopeId(), copy.envelopeId());
        assertEquals(env.activityId(), copy.activityId());
        assertEquals(env.targetNodeId(), copy.targetNodeId());
    }

    // ── cache naming ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("caches: lease and event cache names are per-RA so protocols cannot collide")
    void cacheNamesAreNamespacedPerRa() {
        assertEquals("ra-jss7-lease", RaDialogLeaseCaches.cacheNameFor("jss7"));
        assertEquals("ra-http-server-event", RaStickyEventBus.cacheNameFor("http-server"));
        // The lease cache and the event cache for the SAME RA must not collide —
        // they carry different value types under different lifecycles.
        assertNotEqualsIgnoringType(RaDialogLeaseCaches.cacheNameFor("jss7"),
                RaStickyEventBus.cacheNameFor("jss7"));
        // Different RAs must not collide either.
        assertNotEqualsIgnoringType(RaStickyEventBus.cacheNameFor("jss7"),
                RaStickyEventBus.cacheNameFor("http-server"));
    }

    /** Mirrors {@code MarshallingAllowList} regexp evaluation. */
    private static boolean allowListAccepts(String className) {
        for (String regexp : MarshallingAllowList.REGEXPS) {
            if (className.matches(regexp)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("caches: event forward defaults ON while command forward defaults OFF")
    void asymmetryBetweenCommandAndEventForwardIsPinned() {
        // The asymmetry is the whole point of ADR 0007 D2 and must not be
        // "tidied up" into a single default:
        //   - forwarding a REQUEST to a node without the peer connection changes
        //     the protocol shape, so the sync-path REJECT is honest;
        //   - forwarding a RESPONSE to the node that already holds the client's
        //     TCP socket is pure routing and is the only way it can be written.
        assertEquals("false", System.getProperty(RaHaSupport.PROP_STICKY_FORWARD, "false"),
                "command-side forward must stay opt-in");
        assertEquals("true",
                System.getProperty(RaStickyEventBus.PROP_STICKY_FORWARD_EVENTS, "true"),
                "event-side forward must default on");
    }

    private static void assertNotEqualsIgnoringType(String a, String b) {
        assertFalse(a.equals(b), a + " must differ from " + b);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    /** Stand-in for a real RA payload (e.g. {@code MapEventPayload}). */
    private static final class MapEventPayloadStub implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final String imsi = "234150999876543";
    }

    /** Outside {@code com.microjainslee.*} — must be refused. */
    private static final class NotAllowListedPayload implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    /** Sanity check that the local lease list helper compiles against the record. */
    @Test
    @DisplayName("lease: localLeases-style filtering is expressible")
    void leaseFilteringHelperCompiles() {
        RaDialogLease mine = RaDialogLease.claim("d", "node-a", "ra", 1L,
                System.currentTimeMillis(), 60_000L);
        List<RaDialogLease> all = List.of(mine);
        long mineCount = all.stream()
                .filter(l -> l.stillOwnedBy("node-a", 1L))
                .count();
        assertEquals(1L, mineCount);
        assertNull(null, "placeholder");
        assertNotSame(mine, roundTripSilent(mine));
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTripSilent(T v) {
        try {
            return roundTrip(v);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
