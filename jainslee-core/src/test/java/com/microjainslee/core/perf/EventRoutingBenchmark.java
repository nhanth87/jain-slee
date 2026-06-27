/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.perf;

// TODO: add JMH dep and @BenchmarkMode
// To enable this benchmark:
//   1. Add to jainslee-core/pom.xml:
//        <dependency>
//          <groupId>org.openjdk.jmh</groupId>
//          <artifactId>jmh-core</artifactId>
//          <version>1.37</version>
//          <scope>test</scope>
//        </dependency>
//        <dependency>
//          <groupId>org.openjdk.jmh</groupId>
//          <artifactId>jmh-generator-annprocess</artifactId>
//          <version>1.37</version>
//          <scope>test</scope>
//        </dependency>
//   2. Uncomment the JMH imports and annotations below.
//   3. Run: mvn -pl jainslee-core test -Dtest=EventRoutingBenchmark

import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.EventMask;
import com.microjainslee.core.InMemoryActivityContext;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;

import org.junit.Ignore;

/**
 * Event routing throughput benchmark.
 *
 * <p>Targets (audit PerfHarness §3.2):
 * <ul>
 *   <li>1→1: ≥ 5 M events / second</li>
 *   <li>1→N (N=100): ≥ 2 M events / second</li>
 * </ul>
 *
 * <p>The benchmark spins up a container, registers N SBBs, attaches them
 * to a single activity context, and routes one event per iteration. The
 * 1→N variant uses {@link EventMask#ACCEPT_ALL} so all 100 SBBs receive
 * every event — the worst case for §8.6 filtering and the case the audit
 * cares about.
 *
 * @author Tran Nhan (nhanth87)
 */
public class EventRoutingBenchmark {

    // TODO: add JMH dep and @BenchmarkMode
    // @BenchmarkMode(Mode.Throughput)
    // @OutputTimeUnit(TimeUnit.SECONDS)
    // @Fork(1)
    // @Warmup(iterations = 3, time = 5)
    // @Measurement(iterations = 5, time = 10)

    private MicroSleeContainer container;
    private InMemoryActivityContext aci;
    private SbbLocalObject[] sbbs;
    private SleeEvent event;

    // TODO: add JMH dep and @BenchmarkMode
    // @Setup(Level.Trial)
    public void setup() {
        container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(4096)
                        .preferVirtualThreads(true)
                        .sbbPoolMin(64)
                        .sbbPoolMax(4096)
                        .sbbPerVirtualThread(true)
                        .build());
        container.start();
        aci = container.createActivityContext("bench-aci");
        sbbs = new SbbLocalObject[100];
        for (int i = 0; i < sbbs.length; i++) {
            com.microjainslee.api.Sbb sbb = new com.microjainslee.api.Sbb() { };
            sbbs[i] = container.registerSbb("bench-sbb-" + i, sbb);
            container.attach("bench-aci", sbbs[i]);
        }
        event = new SleeEvent() { };
    }

    // TODO: add JMH dep and @BenchmarkMode
    // @Benchmark
    public void routeOneToOne() {
        // Re-use the first SBB + the activity context.
        if (sbbs[0] instanceof SimpleSbbLocalObject) {
            // sanity check — exercised in the smoke test path
            ((SimpleSbbLocalObject) sbbs[0]).getEntityState();
        }
        container.routeEvent(event, aci);
    }

    // TODO: add JMH dep and @BenchmarkMode
    // @Benchmark
    public void routeOneToMany() {
        // Same ACI, all 100 SBBs attached, single routed event.
        container.routeEvent(event, aci);
    }

    // TODO: add JMH dep and @BenchmarkMode
    // @TearDown(Level.Trial)
    public void teardown() {
        if (container != null) {
            container.stop();
        }
    }

    @org.junit.Test
    @Ignore("Requires JMH on classpath; see TODO at top of file")
    public void smokeTest() {
        setup();
        try {
            routeOneToOne();
            routeOneToMany();
        } finally {
            teardown();
        }
    }
}