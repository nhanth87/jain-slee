/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core;

import com.microjainslee.ms.core.config.DeploymentConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentConfigTest {

    @Test
    void parseClusterYaml() {
        String yaml = """
                mode: cluster

                nodes:
                  node-1: { host: "10.0.0.1", base-port: 9000 }
                  node-2: { host: "10.0.0.2", base-port: 9000 }

                services:
                  signaling: { node: node-1, transport: INFINISPAN_QUEUE, port: 9001 }
                  app: { node: node-2, transport: INFINISPAN_QUEUE, port: 9002 }
                """;
        DeploymentConfig cfg = DeploymentConfig.loadYaml(yaml, "node-2");
        assertEquals(DeploymentConfig.Mode.CLUSTER, cfg.mode());
        assertTrue(cfg.isLocal("app"));
        assertFalse(cfg.isLocal("signaling"));
        assertEquals("node-1", cfg.getService("signaling").nodeId());
    }

    @Test
    void singleModeEverythingLocal() {
        DeploymentConfig cfg = DeploymentConfig.singleNode();
        assertTrue(cfg.isLocal("anything"));
    }
}
