/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.ms.api.ServiceState;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;
import org.infinispan.commons.marshall.Marshaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression for ISPN000559: Infinispan 15 defaults to ProtoStream, which
 * cannot marshal {@link ServiceStateRecord}. ClusterManager must install
 * {@link JavaSerializationMarshaller} so state-transfer / remote gets work.
 */
class ServiceStateRecordClusterMarshallingTest {

    private ClusterManager clusterManager;

    @AfterEach
    void tearDown() {
        if (clusterManager != null) {
            clusterManager.stop();
            clusterManager = null;
        }
    }

    @Test
    void clusterManagerUsesJavaSerializationAndCanRoundTripStateRecord() throws Exception {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts("127.0.0.1[17999]")
                .nodeId("node-ra")
                .build();
        clusterManager = new ClusterManager(cfg, null);
        clusterManager.start();

        Marshaller marshaller = clusterManager.getCacheManager()
                .getCacheManagerConfiguration()
                .serialization()
                .marshaller();
        assertInstanceOf(JavaSerializationMarshaller.class, marshaller);

        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.publishState("http-ra", ServiceState.READY);
        assertEquals(ServiceState.READY, transport.stateOf("http-ra"));

        ServiceStateRecord original = transport.stateCache().get("http-ra");
        assertNotNull(original);

        // Same path used during JGroups state transfer / remote GetKeyValue.
        byte[] bytes = marshaller.objectToByteBuffer(original);
        Object restored = marshaller.objectFromByteBuffer(bytes);
        assertInstanceOf(ServiceStateRecord.class, restored);
        ServiceStateRecord copy = (ServiceStateRecord) restored;
        assertEquals("http-ra", copy.serviceName());
        assertEquals(ServiceState.READY, copy.state());
        assertEquals("node-ra", copy.nodeId());
    }

    @Test
    void sleeQueueEntryAlsoRoundTrips() throws Exception {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .clusterEnabled(true)
                .clusterStack("tcp")
                .clusterInitialHosts("127.0.0.1[17998]")
                .nodeId("node-sbb")
                .build();
        clusterManager = new ClusterManager(cfg, null);
        clusterManager.start();

        Marshaller marshaller = clusterManager.getCacheManager()
                .getCacheManagerConfiguration()
                .serialization()
                .marshaller();

        SleeQueueEntry entry = SleeQueueEntry.ofRequest(
                new com.microjainslee.ms.api.SleeRequest("ping", new byte[]{1, 2}),
                "node-sbb",
                false);
        byte[] bytes = marshaller.objectToByteBuffer(entry);
        SleeQueueEntry copy = (SleeQueueEntry) marshaller.objectFromByteBuffer(bytes);
        assertEquals("ping", copy.operation());
        assertEquals(SleeQueueEntry.EntryType.REQUEST, copy.type());
    }
}
