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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link MicroSleeContainer#bindActivityContextNamingFacility(Object)}
 * correctly wires a {@link ClusteredActivityContextNamingFacility} into the
 * kernel so that subsequent {@code createActivityContext} / {@code lookup} calls
 * hit the Infinispan cache.
 *
 * <p>This is the reflective contract between jainslee-core and jainslee-cluster:
 * the kernel never imports the cluster class, but accepts an instance of it
 * and routes all ACNF operations through the reflective adapter.
 */
class MicroSleeContainerAcnfWireTest {

    private MicroSleeContainer container;
    private ClusterManager clusterManager;
    private ClusteredActivityContextNamingFacility facility;

    @BeforeEach
    void setUp() {
        // Use a unique TCP port range so concurrent test runs do not
        // collide on the JGroups server socket.
        int port = 7810 + (int) (Math.random() * 180);
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts("127.0.0.1[" + port + "]")
                .nodeId("wire-test-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        this.clusterManager = new ClusterManager(cfg, null);
        this.clusterManager.start();
        this.facility = new ClusteredActivityContextNamingFacility(clusterManager);
        this.container = new MicroSleeContainer();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            try { container.stop(); } catch (RuntimeException ignored) { }
        }
        if (clusterManager != null) {
            try { clusterManager.stop(); } catch (RuntimeException ignored) { }
        }
    }

    @Test
    @DisplayName("bindActivityContextNamingFacility installs the cluster facility into the kernel")
    void wireInstallsClusterFacility() {
        container.bindActivityContextNamingFacility(facility);
        // After binding, the kernel reports the (reflexive wrapper around the)
        // cluster facility.
        assertThat(container.getActivityContextNamingFacility()).isNotNull();
    }

    @Test
    @DisplayName("Reaching the cluster facility through the kernel hits the Infinispan cache")
    void clusterFacilityRoundTripThroughKernel() {
        container.bindActivityContextNamingFacility(facility);
        // Use the kernel's reflective adapter to bind a name, then look
        // it up via the cluster facility directly. The two views must
        // agree because they share the same Infinispan cache.
        ActivityContextInterface aci = ClusterTestUtil.newSerializableAci("wire-key");
        MicroSleeContainer.AcnfBackend kernelFac = container.getActivityContextNamingFacility();
        kernelFac.bind("wire-key", aci);

        // Verify both views see the same binding.
        ActivityContextInterface fromKernel = kernelFac.lookup("wire-key");
        ActivityContextInterface fromCluster = facility.lookup("wire-key");
        assertThat(fromKernel).isNotNull();
        assertThat(fromCluster).isNotNull();
        assertThat(fromKernel.getActivityContextName()).isEqualTo("wire-key");
        assertThat(fromCluster.getActivityContextName()).isEqualTo("wire-key");
    }

    @Test
    @DisplayName("kernel-bound names appear in facility.names()")
    void namesVisibleAcross() {
        container.bindActivityContextNamingFacility(facility);
        MicroSleeContainer.AcnfBackend kernelFac = container.getActivityContextNamingFacility();
        ActivityContextInterface a = ClusterTestUtil.newSerializableAci("a");
        ActivityContextInterface b = ClusterTestUtil.newSerializableAci("b");
        kernelFac.bind("a", a);
        kernelFac.bind("b", b);

        Set<String> fromCluster = new HashSet<String>(facility.names());
        assertThat(fromCluster).contains("a", "b");
    }

    @Test
    @DisplayName("unbind through the kernel removes the binding in the cluster cache")
    void unbindRemovesFromClusterCache() {
        container.bindActivityContextNamingFacility(facility);
        MicroSleeContainer.AcnfBackend kernelFac = container.getActivityContextNamingFacility();
        ActivityContextInterface aci = ClusterTestUtil.newSerializableAci("transient");
        kernelFac.bind("transient", aci);
        assertThat(facility.lookup("transient")).isNotNull();

        kernelFac.unbind("transient");
        assertThat(facility.lookup("transient")).isNull();
    }

    @Test
    @DisplayName("bindActivityContextNamingFacility(null) reverts to the in-memory facility")
    void clearingBindingRevertsToInMemory() {
        container.bindActivityContextNamingFacility(facility);
        // After clearing, the kernel's facility is a fresh in-memory
        // facility (different object from the cluster one).
        Object clusterView = container.getActivityContextNamingFacility();
        container.bindActivityContextNamingFacility(null);
        Object inMemoryView = container.getActivityContextNamingFacility();
        assertThat(inMemoryView).isNotNull();
        // The two views are distinct: the in-memory one is a brand-new
        // instance, not the cluster one.
        assertThat(inMemoryView).isNotSameAs(clusterView);
    }

    @Test
    @DisplayName("bindActivityContextNamingFacility rejects wrong type with IllegalArgumentException")
    void rejectsWrongType() {
        Object wrongType = new Object();
        assertThatThrownBy(() -> container.bindActivityContextNamingFacility(wrongType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ClusteredActivityContextNamingFacility");
    }
}
