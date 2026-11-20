/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.bench;

import java.util.concurrent.TimeUnit;

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Pending-issues P4 — event-router throughput baseline (ADR 0007 D7).
 *
 * <p>Two paths: 1→1 (one SBB on the activity) and 1→N (100 SBBs on the same
 * activity, the worst case for attachment sorting). The SBBs do nothing but
 * return, so this measures router + entity-handoff overhead, not app work.
 * Telemetry observers are unbound (the telemetry-bound path is a separate
 * benchmark once this baseline is stable).
 *
 * <p>Run: {@code mvn -pl jainslee-bench -am package -DskipTests} then
 * {@code java --add-modules jdk.sctp -jar jainslee-bench/target/benchmarks.jar}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
public class EventRouterBench {

    private MicroSleeContainer container;
    private com.microjainslee.api.ActivityContextInterface oneToOne;
    private com.microjainslee.api.ActivityContextInterface oneToMany;
    private SleeEvent event;

    @Setup
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
        oneToOne = container.createActivityContext("bench-1-1");
        SbbLocalObject single = container.registerSbb("bench-single", noopSbb());
        container.attach("bench-1-1", single);
        oneToMany = container.createActivityContext("bench-1-n");
        for (int i = 0; i < 100; i++) {
            container.attach("bench-1-n", container.registerSbb("bench-sbb-" + i, noopSbb()));
        }
        event = new SleeEvent() {
        };
    }

    @TearDown
    public void teardown() {
        if (container != null) {
            container.stop();
        }
    }

    @Benchmark
    public void routeOneToOne() {
        container.routeEvent(event, oneToOne);
    }

    @Benchmark
    public void routeOneToMany() {
        container.routeEvent(event, oneToMany);
    }

    private static Sbb noopSbb() {
        return new Sbb() {
        };
    }
}
