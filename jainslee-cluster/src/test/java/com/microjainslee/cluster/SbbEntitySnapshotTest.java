/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SbbEntitySnapshot}.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Required-field guards (null {@code sbbClassFqn}, null
 *       {@code sbbId}).</li>
 *   <li>Defensive copies on {@code cmpFieldValues} and
 *       {@code attachedAciNames} - mutating the input map after
 *       construction must NOT affect the snapshot.</li>
 *   <li>Empty / null input collapse to {@link Collections#emptyMap()}
 *       and {@link Collections#emptySet()}.</li>
 *   <li>Java-serialization round-trip preserves every field.</li>
 *   <li>{@code equals}/{@code hashCode}/{@code toString} sanity
 *       checks.</li>
 * </ol>
 *
 * <p><b>R&amp;D only &mdash; never for production.</b>
 */
class SbbEntitySnapshotTest {

    @Test
    @DisplayName("Constructor requires non-null sbbClassFqn and sbbId")
    void requiredFields() {
        assertThatThrownBy(() -> new SbbEntitySnapshot(
                null, "id-1", null, null, 0L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sbbClassFqn");
        assertThatThrownBy(() -> new SbbEntitySnapshot(
                "com.example.Sbb", null, null, null, 0L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sbbId");
    }

    @Test
    @DisplayName("Empty / null input collapses to empty immutable collections")
    void emptyInput() {
        SbbEntitySnapshot snap = new SbbEntitySnapshot(
                "com.example.Sbb", "id-1", null, null, 42L);
        assertThat(snap.getCmpFieldValues()).isEmpty();
        assertThat(snap.getAttachedAciNames()).isEmpty();
        // The returned collections must be immutable so a downstream
        // bug cannot mutate shared snapshot state.
        assertThatThrownBy(() -> snap.getCmpFieldValues().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snap.getAttachedAciNames().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Constructor takes defensive copies of input collections")
    void defensiveCopies() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("balance", 100);
        inputs.put("msisdn", "+841234");
        Set<String> acis = new LinkedHashSet<>();
        acis.add("aci-1");
        acis.add("aci-2");
        SbbEntitySnapshot snap = new SbbEntitySnapshot(
                "com.example.Sbb", "id-1", inputs, acis, 42L);

        // Mutate the originals after construction.
        inputs.put("balance", 999);
        inputs.put("extra", "should-not-leak");
        acis.add("aci-3");
        acis.remove("aci-1");

        // Snapshot is untouched.
        assertThat(snap.getCmpFieldValues())
                .containsOnlyKeys("balance", "msisdn")
                .containsEntry("balance", 100)
                .containsEntry("msisdn", "+841234");
        assertThat(snap.getAttachedAciNames()).containsExactly("aci-1", "aci-2");
    }

    @Test
    @DisplayName("Java-serialization round-trip preserves every field")
    void serializeRoundTrip() throws Exception {
        Map<String, Object> cmp = new LinkedHashMap<>();
        cmp.put("balance", 12345);
        cmp.put("msisdn", "+841234567");
        cmp.put("active", Boolean.TRUE);
        Set<String> acis = new LinkedHashSet<>();
        acis.add("aci-a");
        acis.add("aci-b");
        SbbEntitySnapshot original = new SbbEntitySnapshot(
                "com.example.BankSbb", "sbb-42",
                cmp, acis, 1_700_000_000_000L);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }
        byte[] bytes = baos.toByteArray();
        assertThat(bytes).isNotEmpty();

        SbbEntitySnapshot restored;
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            restored = (SbbEntitySnapshot) ois.readObject();
        }

        assertThat(restored).isEqualTo(original);
        assertThat(restored.getSbbClassFqn()).isEqualTo("com.example.BankSbb");
        assertThat(restored.getSbbId()).isEqualTo("sbb-42");
        assertThat(restored.getCmpFieldValues())
                .containsEntry("balance", 12345)
                .containsEntry("msisdn", "+841234567")
                .containsEntry("active", Boolean.TRUE);
        assertThat(restored.getAttachedAciNames())
                .containsExactlyInAnyOrder("aci-a", "aci-b");
        assertThat(restored.getSnapshotTimestamp()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    @DisplayName("equals / hashCode / toString follow the POJO contract")
    void equalsHashCodeToString() {
        SbbEntitySnapshot a = new SbbEntitySnapshot(
                "com.example.Sbb", "id-1",
                Map.of("k", 1), Set.of("aci"), 10L);
        SbbEntitySnapshot b = new SbbEntitySnapshot(
                "com.example.Sbb", "id-1",
                Map.of("k", 1), Set.of("aci"), 10L);
        SbbEntitySnapshot c = new SbbEntitySnapshot(
                "com.example.Sbb", "id-2",
                Map.of("k", 1), Set.of("aci"), 10L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
        assertThat(a.toString()).contains("com.example.Sbb", "id-1");
    }
}
