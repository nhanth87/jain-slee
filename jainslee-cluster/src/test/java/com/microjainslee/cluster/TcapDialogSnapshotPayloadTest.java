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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pending-issues #3 / ADR 0007 Q — the portable TCAP dialog snapshot carries
 * the app correlation id and the captured outstanding invokes.
 *
 * <p>Pins the round-trip of the new fields, the legacy-constructor defaults,
 * equality/hashCode coverage, defensive array copies, and the
 * {@code hasUnrestorablePendingInvokes} takeover-refusal rule.
 */
class TcapDialogSnapshotPayloadTest {

    private static final TcapDialogSnapshotPayload.PortableSccpAddress LOCAL =
            TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(1, 8);
    private static final TcapDialogSnapshotPayload.PortableSccpAddress REMOTE =
            TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(2, 9);

    private static TcapDialogSnapshotPayload fullPayload(String appCorrelationId,
            TcapDialogSnapshotPayload.PendingInvokeState[] pendingInvokes) {
        return new TcapDialogSnapshotPayload(
                "otid:42", 42L, new byte[]{0x01, 0x02}, LOCAL, REMOTE, "SENT",
                new long[]{0x1234L, 0x5678L}, 1_000L, 7, 8, 2, 3, false,
                new boolean[]{true, false, true}, 1_700_000_000_000L, "asp-1",
                1_700_000_000_001L, pendingInvokes, appCorrelationId);
    }

    private static TcapDialogSnapshotPayload payloadWithInvokes(
            boolean[] invokeIdTaken,
            TcapDialogSnapshotPayload.PendingInvokeState[] pendingInvokes) {
        return new TcapDialogSnapshotPayload(
                "otid:42", 42L, new byte[]{0x01}, LOCAL, REMOTE, "SENT",
                new long[]{1L}, 1L, 7, 8, 2, 3, false,
                invokeIdTaken, 1L, null, 0L, pendingInvokes, "app-1");
    }

    @Test
    @DisplayName("full constructor round-trips appCorrelationId, wall-clock deadline and pending invokes")
    void fullConstructorRoundTripsNewFields() {
        TcapDialogSnapshotPayload.PendingInvokeState[] pending =
                {new TcapDialogSnapshotPayload.PendingInvokeState(1, 2, 3L, 5_000L, 4_000L)};
        TcapDialogSnapshotPayload payload = fullPayload("gmlc-uuid-1", pending);

        assertThat(payload.appCorrelationId()).isEqualTo("gmlc-uuid-1");
        assertThat(payload.idleDeadlineEpochMs()).isEqualTo(1_700_000_000_001L);
        assertThat(payload.pendingInvokes()).containsExactly(pending[0]);
        assertThat(payload.preferredAspName()).isEqualTo("asp-1");
    }

    @Test
    @DisplayName("legacy constructors leave appCorrelationId null and pending invokes uncaptured")
    void legacyConstructorsLeaveNewFieldsNull() {
        TcapDialogSnapshotPayload legacy14 = new TcapDialogSnapshotPayload(
                "otid:1", 1L, new byte[]{1}, LOCAL, REMOTE, "SENT",
                new long[]{1L}, 1L, 1, 8, 2, 3, false, new boolean[]{true}, 1L);
        assertThat(legacy14.appCorrelationId()).isNull();
        assertThat(legacy14.idleDeadlineEpochMs()).isZero();
        assertThat(legacy14.pendingInvokes()).isNull();

        TcapDialogSnapshotPayload legacy15 = new TcapDialogSnapshotPayload(
                "otid:1", 1L, new byte[]{1}, LOCAL, REMOTE, "SENT",
                new long[]{1L}, 1L, 1, 8, 2, 3, false, new boolean[]{true}, 1L, "asp-1");
        assertThat(legacy15.appCorrelationId()).isNull();
        assertThat(legacy15.idleDeadlineEpochMs()).isZero();
        assertThat(legacy15.pendingInvokes()).isNull();
        assertThat(legacy15.preferredAspName()).isEqualTo("asp-1");
    }

    @Test
    @DisplayName("equals/hashCode include appCorrelationId")
    void equalsAndHashCodeIncludeAppCorrelationId() {
        TcapDialogSnapshotPayload.PendingInvokeState[] pending =
                {new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, null, 1L, 1L)};
        TcapDialogSnapshotPayload a = fullPayload("app-A", pending);
        TcapDialogSnapshotPayload b = fullPayload("app-A", pending);
        TcapDialogSnapshotPayload other = fullPayload("app-B", pending);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a.hashCode()).isNotEqualTo(other.hashCode());
    }

    @Test
    @DisplayName("equals/hashCode include pendingInvokes")
    void equalsAndHashCodeIncludePendingInvokes() {
        TcapDialogSnapshotPayload a = fullPayload("app",
                new TcapDialogSnapshotPayload.PendingInvokeState[]{
                        new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, 10L, 100L, 90L)});
        TcapDialogSnapshotPayload b = fullPayload("app",
                new TcapDialogSnapshotPayload.PendingInvokeState[]{
                        new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, 10L, 100L, 90L)});
        TcapDialogSnapshotPayload differentOp = fullPayload("app",
                new TcapDialogSnapshotPayload.PendingInvokeState[]{
                        new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, 11L, 100L, 90L)});
        TcapDialogSnapshotPayload uncaptured = fullPayload("app", null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(differentOp);
        assertThat(a).isNotEqualTo(uncaptured);
    }

    @Test
    @DisplayName("constructor clones source arrays; getters return fresh clones")
    void defensiveCopies() {
        byte[] remoteOtid = {1, 2, 3};
        long[] applicationContextOid = {10L, 20L};
        boolean[] invokeIdTaken = {true, false};
        TcapDialogSnapshotPayload.PendingInvokeState[] pending =
                {new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, null, 10L, 5L)};

        TcapDialogSnapshotPayload payload = new TcapDialogSnapshotPayload(
                "otid:1", 1L, remoteOtid, LOCAL, REMOTE, "SENT",
                applicationContextOid, 1L, 1, 8, 2, 3, false,
                invokeIdTaken, 1L, null, 0L, pending, "app-1");

        // Mutate every source array after construction — the payload must not change.
        remoteOtid[0] = 99;
        applicationContextOid[0] = 99L;
        invokeIdTaken[0] = false;
        pending[0] = new TcapDialogSnapshotPayload.PendingInvokeState(9, 9, 9L, 9L, 9L);

        assertThat(payload.remoteOtid()).containsExactly(1, 2, 3);
        assertThat(payload.applicationContextOid()).containsExactly(10L, 20L);
        assertThat(payload.invokeIdTaken()).containsExactly(true, false);
        assertThat(payload.pendingInvokes())
                .containsExactly(new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, null, 10L, 5L));

        // Returned arrays are clones: mutating them must not affect the payload.
        assertThat(payload.remoteOtid()).isNotSameAs(payload.remoteOtid());
        byte[] returnedOtid = payload.remoteOtid();
        returnedOtid[0] = 7;
        assertThat(payload.remoteOtid()).containsExactly(1, 2, 3);

        long[] returnedOid = payload.applicationContextOid();
        returnedOid[0] = 7L;
        assertThat(payload.applicationContextOid()).containsExactly(10L, 20L);

        boolean[] returnedTaken = payload.invokeIdTaken();
        returnedTaken[0] = false;
        assertThat(payload.invokeIdTaken()).containsExactly(true, false);

        TcapDialogSnapshotPayload.PendingInvokeState[] returnedPending = payload.pendingInvokes();
        returnedPending[0] = new TcapDialogSnapshotPayload.PendingInvokeState(9, 9, 9L, 9L, 9L);
        assertThat(payload.pendingInvokes())
                .containsExactly(new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, null, 10L, 5L));
    }

    @Test
    @DisplayName("hasUnrestorablePendingInvokes: no taken invoke ids -> false")
    void noTakenInvokeIdsIsNotUnrestorable() {
        assertThat(payloadWithInvokes(new boolean[]{false, false}, null)
                .hasUnrestorablePendingInvokes()).isFalse();
        assertThat(payloadWithInvokes(null, null).hasUnrestorablePendingInvokes()).isFalse();
    }

    @Test
    @DisplayName("hasUnrestorablePendingInvokes: taken bit with null pendingInvokes -> true")
    void takenBitWithNullPendingInvokesIsUnrestorable() {
        assertThat(payloadWithInvokes(new boolean[]{true, false}, null)
                .hasUnrestorablePendingInvokes()).isTrue();
    }

    @Test
    @DisplayName("hasUnrestorablePendingInvokes: pendingInvokes shorter than taken count -> true")
    void shorterPendingInvokesIsUnrestorable() {
        TcapDialogSnapshotPayload payload = payloadWithInvokes(
                new boolean[]{true, true},
                new TcapDialogSnapshotPayload.PendingInvokeState[]{
                        new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, null, 1L, 1L)});
        assertThat(payload.hasPendingInvokes()).isTrue();
        assertThat(payload.hasUnrestorablePendingInvokes()).isTrue();
    }

    @Test
    @DisplayName("hasUnrestorablePendingInvokes: pendingInvokes length >= taken count -> false")
    void enoughPendingInvokesIsRestorable() {
        TcapDialogSnapshotPayload.PendingInvokeState[] pending =
                {new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, null, 1L, 1L),
                        new TcapDialogSnapshotPayload.PendingInvokeState(2, 1, null, 1L, 1L)};
        TcapDialogSnapshotPayload twoTaken = payloadWithInvokes(new boolean[]{true, true}, pending);
        assertThat(twoTaken.hasUnrestorablePendingInvokes()).isFalse();

        // Three taken slots, two taken bits, exactly two captured invokes.
        TcapDialogSnapshotPayload twoOfThree = payloadWithInvokes(new boolean[]{true, false, true}, pending);
        assertThat(twoOfThree.hasUnrestorablePendingInvokes()).isFalse();
    }

    @Test
    @DisplayName("pendingInvokes() returns a clone; null stays null")
    void pendingInvokesReturnsCloneOrNull() {
        assertThat(fullPayload("app", null).pendingInvokes()).isNull();

        TcapDialogSnapshotPayload.PendingInvokeState[] pending =
                {new TcapDialogSnapshotPayload.PendingInvokeState(1, 1, 1L, 1L, 1L)};
        TcapDialogSnapshotPayload payload = fullPayload("app", pending);

        TcapDialogSnapshotPayload.PendingInvokeState[] first = payload.pendingInvokes();
        assertThat(first).isNotSameAs(payload.pendingInvokes());
        assertThat(first).containsExactly(pending[0]);

        first[0] = new TcapDialogSnapshotPayload.PendingInvokeState(9, 9, 9L, 9L, 9L);
        assertThat(payload.pendingInvokes()).containsExactly(pending[0]);
    }
}
