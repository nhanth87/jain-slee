/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.profile.infinispan;

import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileMutation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit / integration tests for {@link InfinispanProfileStore}. The central
 * scenario is a <b>restart-survival simulation</b>: write rows, {@code close()}
 * the store (JVM stop), open a fresh store over the same directory, and assert
 * every row and JDK-only value type is intact.
 */
class InfinispanProfileStoreTest {

    private static final String TABLE = "SubscriberCore";

    private static ProfileID id(String name) {
        return new ProfileID(TABLE, name);
    }

    @Test
    void storeThenLoadRoundTrips(@TempDir Path dir) {
        try (InfinispanProfileStore store = new InfinispanProfileStore(dir)) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("msisdn", "84901234567");
            fields.put("balance", 12_345L);
            fields.put("active", Boolean.TRUE);
            store.storeFields(id("alice"), fields);

            Map<String, Object> loaded = store.loadFields(id("alice"));
            assertThat(loaded)
                    .containsEntry("msisdn", "84901234567")
                    .containsEntry("balance", 12_345L)
                    .containsEntry("active", Boolean.TRUE);
        }
    }

    @Test
    void loadMissingRowReturnsNull(@TempDir Path dir) {
        try (InfinispanProfileStore store = new InfinispanProfileStore(dir)) {
            assertThat(store.loadFields(id("ghost"))).isNull();
        }
    }

    @Test
    void removeDeletesRow(@TempDir Path dir) {
        try (InfinispanProfileStore store = new InfinispanProfileStore(dir)) {
            store.storeFields(id("bob"), Map.of("x", 1));
            assertThat(store.loadFields(id("bob"))).isNotNull();
            store.remove(id("bob"));
            assertThat(store.loadFields(id("bob"))).isNull();
        }
    }

    @Test
    void storeBatchAppliesUpsertsAndDeletesInOrder(@TempDir Path dir) {
        try (InfinispanProfileStore store = new InfinispanProfileStore(dir)) {
            store.storeFields(id("carol"), Map.of("v", "old"));

            List<ProfileMutation> batch = new ArrayList<>();
            batch.add(ProfileMutation.upsert(id("dave"), Map.of("v", "new")));
            // Later mutation on the same id must win (order preserved).
            batch.add(ProfileMutation.upsert(id("dave"), Map.of("v", "newer")));
            batch.add(ProfileMutation.delete(id("carol")));
            store.storeBatch(batch);

            assertThat(store.loadFields(id("carol"))).isNull();
            assertThat(store.loadFields(id("dave"))).containsEntry("v", "newer");
        }
    }

    @Test
    void listProfileNamesAndLoadTable(@TempDir Path dir) {
        try (InfinispanProfileStore store = new InfinispanProfileStore(dir)) {
            store.storeFields(id("a"), Map.of("n", 1));
            store.storeFields(id("b"), Map.of("n", 2));

            assertThat(store.listProfileNames(TABLE)).containsExactlyInAnyOrder("a", "b");

            Map<String, Map<String, Object>> table = store.loadTable(TABLE);
            assertThat(table).hasSize(2);
            assertThat(table.get("a")).containsEntry("n", 1);
            assertThat(table.get("b")).containsEntry("n", 2);
        }
    }

    /**
     * The core Phase 4 guarantee: data written by one store instance is visible
     * to a brand-new instance over the same directory after a clean close —
     * i.e. it survives a JVM restart.
     */
    @Test
    void dataSurvivesRestart(@TempDir Path dir) {
        // ---- first "JVM": write a variety of JDK-only value types ----
        Map<String, Object> fields = new HashMap<>();
        fields.put("msisdn", "84900000001");
        fields.put("imsi", 452040000000001L);
        fields.put("credit", 999);
        fields.put("suspended", Boolean.FALSE);
        fields.put("token", new byte[] {1, 2, 3, 4});
        fields.put("services", List.of("VOICE", "SMS", "DATA"));
        Map<String, Object> nested = new HashMap<>();
        nested.put("mcc", "452");
        nested.put("mnc", "04");
        fields.put("plmn", nested);

        try (InfinispanProfileStore store = new InfinispanProfileStore(dir)) {
            store.storeFields(id("subscriber-1"), fields);
            store.storeFields(id("subscriber-2"), Map.of("msisdn", "84900000002"));
        }

        // ---- second "JVM": fresh cache manager over the same files ----
        try (InfinispanProfileStore restarted = new InfinispanProfileStore(dir)) {
            Map<String, Object> loaded = restarted.loadFields(id("subscriber-1"));
            assertThat(loaded).isNotNull();
            assertThat(loaded).containsEntry("msisdn", "84900000001");
            assertThat(loaded).containsEntry("imsi", 452040000000001L);
            assertThat(loaded).containsEntry("credit", 999);
            assertThat(loaded).containsEntry("suspended", Boolean.FALSE);
            assertThat((byte[]) loaded.get("token")).containsExactly(1, 2, 3, 4);
            @SuppressWarnings("unchecked")
            List<String> services = (List<String>) loaded.get("services");
            assertThat(services).containsExactly("VOICE", "SMS", "DATA");
            @SuppressWarnings("unchecked")
            Map<String, Object> plmn = (Map<String, Object>) loaded.get("plmn");
            assertThat(plmn)
                    .containsEntry("mcc", "452")
                    .containsEntry("mnc", "04");

            // Eager rehydration sees every persisted row (Contract C2).
            assertThat(restarted.listProfileNames(TABLE))
                    .containsExactlyInAnyOrder("subscriber-1", "subscriber-2");
        }
    }

    @Test
    void removalSurvivesRestart(@TempDir Path dir) {
        try (InfinispanProfileStore store = new InfinispanProfileStore(dir)) {
            store.storeFields(id("temp"), Map.of("v", 1));
            store.remove(id("temp"));
        }
        try (InfinispanProfileStore restarted = new InfinispanProfileStore(dir)) {
            assertThat(restarted.loadFields(id("temp"))).isNull();
        }
    }
}
