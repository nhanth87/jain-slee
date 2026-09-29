# Pending issues — active/active (ADR 0007)

- **Updated:** 2026-09-30
- **Source:** [ADR 0007](adr/0007-true-active-active.md) · lab script
  [active-active-two-node.md](lab/active-active-two-node.md)
- **State:** P0–P2 (correctness) implemented and tested in-process on real
  JGroups members. **No active/active claim is valid until P6 passes on real hosts.**

Priority: **P1** blocks an active/active claim · **P2** needed for production ·
**P3** improvement.

## Open

| # | Pri | Issue | Why it matters | Where / next step |
|---|-----|-------|----------------|-------------------|
| 1 | P1 | **Lab P6 not run** | Everything is proven in one JVM only. Prove-the-artifact law: no claim without a packaged dist, restarted processes, live logs, pcap | Run scenarios A → B → D/E/F → G/H → C/C2 per the lab script, witness on a third host; fill the *Result log* |
| 2 | P1 | **Witness deployment not packaged** | `ClusterWitness` exists but there is no `dist/` entry / `run.sh` / systemd unit for it | Add a witness dist (cluster module + log4j2 only), JDK 25 guard in `run.sh` |
| 3 | P2 | **App correlation lost on takeover** | The MAP helpers' `appDialogId ↔ OTID` maps and MAP dialog state (M5: `origReference`, `destReference`, ERI, ext container) are node-local. After takeover the survivor publishes on the OTID, not the app id | Snapshot the correlation id with the TCAP snapshot; rebuild MAP dialog state for multi-step flows (ATI / sendRoamingNumber). Until then: single-op flows only, client retries with its `activityId` |
| 4 | P2 | **Timer deadlines not replicated (D6)** | A T-timer armed on a dead node dies with it. Node id and unique per-SBB timer ids are fixed; replication is not | Replicate absolute deadlines; fire guarded by the dialog lease (D6) |
| 5 | P2 | **Performance not measured (P4)** | Every D7 throughput target is a projection. JMH benchmarks are `@Ignore`d | Separate `jainslee-bench` module (JMH), record baseline in lab §7 first |
| 6 | P2 | **Single disruptor worker + 30 s latch (SYNC)** | Holds the router for the duration of `onEvent` (not the MAP round trip); head-of-line blocking behind any blocking SBB | After #5: `event-router.shards` (default 1), per-SBB `ASYNC_COMMIT`, cache sorted attachment list |
| 7 | P2 | **Off-heap bytes absent from `SbbEntitySnapshot`** | `@OffHeap` SBBs are non-failoverable | Fail-fast `@OffHeap` + `clusterEnabled`; slot-byte snapshot only when a real SBB needs it (D5) |
| 8 | P2 | **Per-node app state is N× under active/active** | Rate limiters multiply by node count; caches diverge | Outside this repo: ussd-service / ota — limiter /N or clustered counter; breakers stay per-node |
| 9 | P2 | **`flock /tmp/ussdgw.lock` in ussdgw systemd unit** | Blocks two instances on one host only; not an active/active blocker across hosts | Outside this repo: lock name per node id, do not delete |
| 10 | P3 | **Reflection in `jainslee-core` cluster seams (O)** | Violates the core no-reflection rule | `ClusterPort` SPI in core, implemented in `jainslee-cluster`, loaded by `ServiceLoader` |
| 11 | P3 | **Container ingress requires a `SleeEvent` payload (A)** | Unused by SS7 under D11, but any other RA forwarding a portable POJO is dropped | Portable-event codec SPI, or retire the ingress in favour of `ClusterUnicast`. Envelope leak (K) already fixed |
| 12 | P3 | **Flow id + real Counter/Timer instruments (D8)** | A 2-node flow is not one trace; Prometheus computes rates from gauges | `flowId`/`hop` in `EventWrapper`; Micrometer `Counter`/`Timer`; HdrHistogram p99; export lease + foreign-PDU counters as meters |
| 13 | P3 | **Lease heartbeat / GC scan all entries** | `localLeases()` and `collectOrphans()` iterate the whole REPL cache every tick — fine at 10k dialogs, not at 1M | Keep a local index of own leases; expiry-ordered GC |
| 14 | P3 | **`SipRaDialogLifecycleTest.byeKeepsDialogUntilFinalResponseThenEnds` fails** | Pre-existing, unrelated: the test never configures an outbound sender, so the 200 BYE is dropped | Fix the test setup (or the RA's no-sender path) in `ra-sip-servlet` |
| 15 | P3 | **jSS7 `tcap-impl`: 9 pre-existing test failures** | `CreateDialogTest`, 7× `PreviewModeFunctionalTest`, `TCAPAbnormalTest.badAddressMessage2Test` — red on HEAD before ADR 0007 | Triage in `coral-valley/jSS7` |

## Closed in the 2026-09-30 round

jSS7 `j25`: `2204e8fb4` (P0 M1–M4, M6) · `4fbf6c669` (M7 inbound dialog router).
micro-jainslee: D11 symmetric routing, D3/D12 leases + fence + witness, defects
E–M, P, Q, R3, S, T — details and test list in ADR 0007 §Implementation status.
