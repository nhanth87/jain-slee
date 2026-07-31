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

import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.TransportType;
import com.microjainslee.ms.api.exception.CircularDependencyException;
import com.microjainslee.ms.core.dag.ServiceDependencyGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceDependencyGraphTest {

    @Test
    void startOrderLeafFirst() {
        var graph = new ServiceDependencyGraph(List.of(
                desc("a", List.of("b", "c")),
                desc("b", List.of("c", "d")),
                desc("c", List.of()),
                desc("d", List.of())));

        List<String> order = graph.resolveStartOrder();
        assertEquals(4, order.size());
        assertTrue(order.indexOf("d") < order.indexOf("b"));
        assertTrue(order.indexOf("c") < order.indexOf("b"));
        assertTrue(order.indexOf("b") < order.indexOf("a"));
        assertTrue(order.indexOf("c") < order.indexOf("a"));
    }

    @Test
    void stopOrderReverseOfStart() {
        var graph = new ServiceDependencyGraph(List.of(
                desc("a", List.of("b")),
                desc("b", List.of())));
        assertEquals(List.of("a", "b"), graph.resolveStopOrder());
    }

    @Test
    void cycleDetected() {
        assertThrows(CircularDependencyException.class, () ->
                new ServiceDependencyGraph(List.of(
                        desc("a", List.of("b")),
                        desc("b", List.of("a")))).resolveStartOrder());
    }

    private static SleeServiceDescriptor desc(String name, List<String> deps) {
        return new SleeServiceDescriptor(
                name, TransportType.INFINISPAN_QUEUE, deps, List.of(), 100, 30_000L, Object.class);
    }
}
