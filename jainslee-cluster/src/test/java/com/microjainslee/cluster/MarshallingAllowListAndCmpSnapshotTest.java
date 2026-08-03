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

import acme.testdata.ForeignHandle;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.core.MicroSleeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarshallingAllowListAndCmpSnapshotTest {

    private ClusterManager manager;
    private DistributedSbbEntityPool pool;

    @BeforeEach
    void setUp() {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts("127.0.0.1[" + (7900 + (int) (Math.random() * 200)) + "]")
                .nodeId("allow-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        manager = new ClusterManager(cfg, null);
        manager.start();
        pool = new DistributedSbbEntityPool(1, 4, false, manager);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void allowListAcceptsJavaAndMicrojainsleeRejectsForeign() {
        assertThat(MarshallingAllowList.isAllowedClass(String.class)).isTrue();
        assertThat(MarshallingAllowList.isAllowedClass(Integer.class)).isTrue();
        assertThat(MarshallingAllowList.isAllowedClass(SbbEntitySnapshot.class)).isTrue();
        assertThat(MarshallingAllowList.isAllowedClass(byte[].class)).isTrue();
        assertThat(MarshallingAllowList.isAllowedClass(ForeignHandle.class)).isFalse();
        assertThat(MarshallingAllowList.isAllowedClass(org.infinispan.Cache.class)).isFalse();
    }

    @Test
    void takeSnapshotRejectsCmpFieldOutsideAllowList() {
        ForeignCmpSbb sbb = new ForeignCmpSbb();
        sbb.setHandle(new ForeignHandle("x"));
        assertThatThrownBy(() -> pool.takeSnapshot("bad-1", sbb))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow-list");
    }

    @Test
    void takeSnapshotAcceptsCounterSbb() {
        CounterSbb sbb = new CounterSbb();
        sbb.setBalance(42);
        sbb.setMsisdn("8490");
        SbbEntitySnapshot snap = pool.takeSnapshot("ok-1", sbb);
        assertThat(snap.getCmpFieldValues().get("balance")).isEqualTo(42);
        assertThat(snap.getCmpFieldValues().get("msisdn")).isEqualTo("8490");
    }

    public static final class ForeignCmpSbb implements Sbb {
        private ForeignHandle handle;

        @CmpField("handle")
        public ForeignHandle getHandle() {
            return handle;
        }

        @CmpField("handle")
        public void setHandle(ForeignHandle handle) {
            this.handle = handle;
        }
    }
}
