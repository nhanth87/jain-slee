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

import com.microjainslee.core.MicroSleeConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * N–N peer-route LB via Infinispan (any of N candidates — not A-A pair-only / not A-P).
 */
class Ss7PeerRouteAffinityTest {

    private ClusterManager clusterManager;

    @AfterEach
    void tearDown() {
        if (clusterManager != null) {
            clusterManager.stop();
            clusterManager = null;
        }
    }

    @Test
    @DisplayName("N=3 round-robin cycles across all candidates")
    void roundRobinAcrossThree() {
        clusterManager = new ClusterManager(MicroSleeConfiguration.builder().build(), "lb-node");
        Ss7PeerRouteAffinity affinity = new Ss7PeerRouteAffinity(clusterManager);

        List<Ss7PeerRouteAffinity.PeerRoute> candidates = List.of(
                new Ss7PeerRouteAffinity.PeerRoute("L1-1404-ASP", 1404),
                new Ss7PeerRouteAffinity.PeerRoute("L2-1403-ASP", 1403),
                new Ss7PeerRouteAffinity.PeerRoute("L3-1405-ASP", 1405));

        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Ss7PeerRouteAffinity.PeerRoute r = affinity.pickAndPin("ni:net=0", null, candidates);
            seen.add(r.aspName());
            order.add(r.aspName());
        }
        assertThat(seen).containsExactlyInAnyOrder(
                "L1-1404-ASP", "L2-1403-ASP", "L3-1405-ASP");
        // RR: first three distinct, next three repeat
        assertThat(order.subList(0, 3)).containsExactlyInAnyOrderElementsOf(seen);
        assertThat(order.get(3)).isEqualTo(order.get(0));
        assertThat(order.get(4)).isEqualTo(order.get(1));
        assertThat(order.get(5)).isEqualTo(order.get(2));
    }

    @Test
    @DisplayName("affinityKey pins same route on subsequent picks (cluster-wide sticky pin)")
    void affinityPinSticky() {
        clusterManager = new ClusterManager(MicroSleeConfiguration.builder().build(), "lb-pin");
        Ss7PeerRouteAffinity affinity = new Ss7PeerRouteAffinity(clusterManager);

        List<Ss7PeerRouteAffinity.PeerRoute> candidates = List.of(
                new Ss7PeerRouteAffinity.PeerRoute("ASP-A", 1404),
                new Ss7PeerRouteAffinity.PeerRoute("ASP-B", 1403),
                new Ss7PeerRouteAffinity.PeerRoute("ASP-C", 1405));

        Ss7PeerRouteAffinity.PeerRoute first =
                affinity.pickAndPin("gtt:0", "corr-msisdn-1", candidates);
        for (int i = 0; i < 20; i++) {
            assertThat(affinity.pickAndPin("gtt:0", "corr-msisdn-1", candidates))
                    .isEqualTo(first);
        }
        // Different affinity → may pick another of N (not forced to same primary)
        Ss7PeerRouteAffinity.PeerRoute other =
                affinity.pickAndPin("gtt:0", "corr-msisdn-2", candidates);
        assertThat(candidates).contains(other);

        affinity.clearPin("gtt:0", "corr-msisdn-1");
    }

    @Test
    @DisplayName("empty candidates rejected (N≥1)")
    void emptyCandidatesRejected() {
        clusterManager = new ClusterManager(MicroSleeConfiguration.builder().build(), "lb-empty");
        Ss7PeerRouteAffinity affinity = new Ss7PeerRouteAffinity(clusterManager);
        assertThatThrownBy(() -> affinity.pickAndPin("ni:0", "x", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }
}
