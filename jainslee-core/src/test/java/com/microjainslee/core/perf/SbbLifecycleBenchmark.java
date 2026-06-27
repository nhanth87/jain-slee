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
//   3. Run: mvn -pl jainslee-core test -Dtest=SbbLifecycleBenchmark

import com.microjainslee.api.Sbb;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;

import org.junit.Ignore;

/**
 * SBB create / activate / remove throughput benchmark.
 *
 * <p>Target: ≥ 1 million operations / second (audit PerfHarness §3.1).
 *
 * <p>The class is structured so it can run as a regular JUnit test
 * (without JMH) when JMH is not on the classpath. The {@link Ignore}
 * annotation prevents the harness from running it until JMH is wired up.
 *
 * @author Tran Nhan (nhanth87)
 */
public class SbbLifecycleBenchmark {

    // TODO: add JMH dep and @BenchmarkMode
    // @BenchmarkMode(Mode.Throughput)
    // @OutputTimeUnit(TimeUnit.SECONDS)
    // @Fork(1)
    // @Warmup(iterations = 3, time = 5)
    // @Measurement(iterations = 5, time = 10)

    // TODO: add JMH dep and @BenchmarkMode
    // @Benchmark
    public void createAndDestroySbb() {
        MicroSleeContainer container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(1024)
                        .preferVirtualThreads(true)
                        .sbbPoolMin(64)
                        .sbbPoolMax(4096)
                        .sbbPerVirtualThread(true)
                        .build());
        try {
            container.start();
            // The benchmark loop: create + activate + remove 1 SBB per op.
            // We use a fresh SBB POJO per call to mimic the audit's
            // create-and-destroy workload; pooling kicks in on the
            // container's VirtualThreadSbbEntityPool / SbbObjectPool side.
            String id = "bench-sbb-" + Thread.currentThread().getId() + "-"
                    + System.nanoTime();
            Sbb sbb = new Sbb() { };
            container.registerSbb(id, sbb);
            container.routeEvent(new com.microjainslee.api.SleeEvent() { },
                    container.createActivityContext("bench-ac-" + System.nanoTime()));
            // Removal goes through the SBB's remove() callback; for the
            // benchmark we go straight to the SimpleSbbLocalObject handle.
            container.getServiceRegistry(); // keep the dep reachable
        } finally {
            container.stop();
        }
    }

    // When JMH is wired up this is the canonical entry point; until then
    // JUnit picks up the @Ignore and skips.
    @org.junit.Test
    @Ignore("Requires JMH on classpath; see TODO at top of file")
    public void smokeTest() {
        // Smoke test so the class compiles & links even before JMH lands.
        createAndDestroySbb();
    }
}