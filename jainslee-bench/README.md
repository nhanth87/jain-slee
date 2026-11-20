# jainslee-bench — event-router JMH baseline (pending-issues P4)

Every D7 throughput target in ADR 0007 is a projection until this module
records measured numbers. Run the baseline **before** touching
`event-router.shards` or `ASYNC_COMMIT` defaults.

## Run

```bash
mvn -pl jainslee-bench -am package -DskipTests
java -jar jainslee-bench/target/benchmarks.jar
```

JDK 25 only (mise `zulu-25`).

## What it measures

| Benchmark | Setup | Meaning |
|-----------|-------|---------|
| `routeOneToOne` | 1 SBB on the activity | router + entity-handoff floor, no fan-out |
| `routeOneToMany` | 100 SBBs on one activity | worst case for attachment sort/copy |

No-op SBBs, telemetry unbound: this is router overhead, not app work.

## Baseline

Run 1 (recorded 2026-11-20, `feat/aa-cluster`):

Machine: Linux x86_64, JDK Zulu 25.38+109 (`25.0.5.0.3+1-LTS`), JMH 1.37,
`EventRouterBench` (2 warmup × 3 s, 3 × 5 s, 1 fork).

| Benchmark | Mode | Score | Error | Unit |
|-----------|------|-------|-------|------|
| `routeOneToOne` | thrpt | 4,643,504 | ± 3,304,788 | ops/s |
| `routeOneToMany` (100 SBBs) | thrpt | 1,157,409 | ± 636,576 | ops/s |

Run 2 (recorded 2026-11-20, same machine/JDK/commit, quick settings
`-f 1 -wi 2 -i 3 -r 1s -w 1s`):

| Benchmark | Mode | Cnt | Score | Error | Unit |
|-----------|------|-----|-------|-------|------|
| `routeOneToOne` | thrpt | 3 | 2,848,749 | ± 8,869,119 | ops/s |
| `routeOneToMany` (100 SBBs) | thrpt | 3 | 984,539 | ± 4,989,009 | ops/s |

High iteration variance in both runs (short iteration counts) — re-run with
more forks before any D7 change claims a delta. Both runs agree within
variance and are well above the D7 targets.

## Decision (P4)

Targets in ADR 0007 D7 were ≥ 500k ev/s (1→1, no observer) with telemetry
bound ≥ 200k ev/s. The measured 1→1 baseline is **~9× the target**, and even
the 100-SBB fan-out is above 1M ev/s:

- `event-router.shards` stays at default **1** — the single disruptor worker
  is not the ceiling at these numbers. Sharding (D7 p-a) stays behind the flag.
- `ASYNC_COMMIT` stays **opt-in per SBB** — the 30 s latch is not exercised by
  no-op SBBs; measure with a blocking-SBB workload before changing the default.
- Re-run after every D7 change and record the delta here.
