# micro-jainslee Improvement Proposal — field lessons + research adaptations

> **Date:** 2026-08-24 · **Branch:** `micro-jainslee-2` · **BOM:** `1.2.0-SNAPSHOT`
> **Author:** synthesized from consumer-tree lessons, runtime code audit, and
> academic / engineering research (SEDA, BBQ, Loom×Netty, io_uring, JDK 25 GC).
> **Status:** Proposed — not yet implemented.
>
> **Ground rule (non-negotiable):** micro-jainslee is the backbone of every
> product tree (ussdgw, OTA, GMLC, elisa/IMS+SMF, chipchipvoice, Nextgen-DRA,
> Nextgen-STP, silent-auth). No change may break an existing feature or regress
> the test baseline. See §6 Non-regression protocol.

Related ADRs: [0004](adr/0004-runtime-wiring-hardening.md) ·
[0005](adr/0005-event-router-load-conditioning.md) ·
[0006](adr/0006-vertx-transport-spine.md)

---

## 1. Lessons inventory (from the field)

Sources: runtime `docs/agents/lessons.md`, workspace lessons,
`worktrees/{ussd-service/ussd-microjainslee, ota-service/ota-sim-push,
gmlc-service/gmlc-microjainslee, voice-service/sip-freeswitch,
voice-service/chipchipvoice, voice-service/smf-microjainslee,
Nextgen-DRA}`, STP DESIGN.md, ADR 0001/0002/0003.

| # | Lesson (repeats in ≥2 trees) | Subsystem stressed |
|---|------------------------------|--------------------|
| L1 | Split-package stub `ProfileAccessorInvoker` in `jainslee-api` wins classpath under Quarkus fast-jar → UOE 500s in production; USSDGW and GMLC both keep a `shadow-profile-accessor.sh` jar-patch step | Profile/API |
| L2 | Any `new InMemoryProfileFacility()` silently rebinds the global `ProfileFieldStoreLocator` → CMP writes miss the created table (`ussdTx` incident) | Profile |
| L3 | SBB instance fields do not correlate (`SbbObjectPool` does not reset; fallback entity id = `Type/acName`) → pull/completion land on two entities (latencyMs=-1, breaker keyed "", retry storms). Every tree hand-rolls an `@ApplicationScoped` registry + TTL sweeper | SBB lifecycle |
| L4 | JVM-local `InMemoryProfileFacility` used as failover SoT (elisa law C9/JP2 forbids it); GMLC built its own ISPN profile store; ussdgw `ussdUser` stays JVM-local | Profile/HA |
| L5 | Rehydrate from store resets AtomicLong/CAS wrappers → dual delivery; each app re-implements claim maps by durable id (`tryClaimMsDigitContinue`, one-in-flight-per-campaign) | Profile/HA |
| L6 | ServiceLoader cannot see `META-INF/services` inside the ROOT app jar under fast-jar layering; every app merges ClassLoaders manually (gmlc `buildHub()`) | SPI/bootstrap |
| L7 | `RaCollector.updateState(raName,state,port)` never fed by container → telemetry UNKNOWN/port=0 forever; each product invents its own `*.live` service | Telemetry/RA |
| L8 | `@InjectRa(name)` mismatch = silent null port; RA activity names must match RA handle ids character-for-character | RA wiring |
| L9 | Forgotten `endActivity` = leak; leak detector exists but not default-on with alarm | RA lifecycle |
| L10 | Infinispan version coupling: Quarkus BOM pulls 16 next to pinned 15 → `NoSuchMethodError`; ussdgw stubbed ClusterManager to escape | Cluster |
| L11 | Elisa N–N mid-call kill: sticky L4 required for normal calls; peer-side dialog restore is the only real fix — SIP has meta checkpoint only, no TCAP-style export/import/restore | HA |
| L12 | STP demands dual HA modes (active-active + active-standby lease-fenced) in one artifact; GMLC operators misread single-endpoint SS7 topology as cluster failure | HA/ops |
| L13 | Every app hand-rolls park/TTL/saga primitives: `ClassicNiHttpPark`, `LocationHttpPark`, `BridgeGateScheduler`, `PendingSriRegistry`, adaptive EWMA gates | Scheduler/SBB |
| L14 | BUILD_TIME knobs (`sbb-pool-max`, Quarkus `db-kind`) cause deploy crash-loops; 10k TPS needs ×10 manual pool bump + repackage | Config/deploy |
| L15 | chipchipvoice speech/tts/wasm RAs copy-adapt the 3-port wrapper by hand; WS endpoint singleton must self-key by connection id | RA kit |

## 2. Proposals

### 🔴 P0 — root causes of production incidents

**P0-1 Kill the split-package ProfileAccessorInvoker stub (L1).**
No throwable stub classes in `jainslee-api`. Real lookup becomes
ServiceLoader-based from core; missing impl fails at boot with a clear message.
Retire `shadow-profile-accessor.sh` in ussdgw/gmlc.

**P0-2 Container-owned profile facility (L2).**
`ProfileFieldStoreLocator` bound per-container, injected, immutable;
`new InMemoryProfileFacility()` global rebind deprecated with ERROR log.

**P0-3 Boot-time wiring validation (L8).**
At `start()`: scan registered SBB types, verify every `@InjectRa(name)` exists
in the RA command-port registry → FAIL boot listing mismatches. Same pass
warns on activity-name conventions that cannot match RA handle ids.

**P0-4 Auto-feed RA state into telemetry (L7).**
Every RA state transition (`registerRa`, stateMachine ACTIVE/STOPPING/ERROR,
port bind) publishes into `RaCollector.updateState` via a default observer —
no more UNKNOWN states.

### 🟠 P1 — SBB lifetime, profile, RA ergonomics

**P1-1 First-class SessionRegistry (L3).** CAS-claim by durable key
(pattern proven by `tryClaimMsDigitContinue`), built-in TTL sweep, bounded
size, metrics, optional ISPN-backed cluster claims. Retires ~10 app-local registries.

**P1-2 Complete entity lifecycle (L9).** Explicit PASSIVATED state (JSR-240
§8.4), per-type idle-eviction contract at the pool (not only StaleDetector's
30-min force-release), `sbbPassivate` checkpoint before slot reclaim, and
router-emitted `OUT SBB=` trace even when a handler dies on `Error`.

**P1-3 `jainslee-profile-jdbc` module (L4).** Write-behind batched store
(100 ms default per profile-programming-model §3), `flushMode=SYNC` opt-in for
billing tables, boot auto-install when a datasource exists, mode
`advisory|authoritative`. Sibling of `jainslee-profile-infinispan`.

**P1-4 Cluster-safe claim primitives (L5).** `claim/renew/release(key,ttl)`
with generation stamps on top of ISPN; regression tests pinning the fixed
`findAccessor` arity behavior.

**P1-5 `@ProfileIndexed` via APT** (currently deferred): generated
`registerIndex` calls at bootstrap.

**P1-6 Standard RaLinkStatusPort (L7 + link-truth law).**
`{live, detail, since, reason}` backed by peer evidence only
(`isM3uaRouteReady`, `isPeerReady`, bound sessions). Monitor, `/health`,
and delivery gates read one source. Honest `live=false` until peer evidence.

**P1-7 SPI discovery merges CLs by default (L6).** TCCL ∪ SPI-CL ∪ app-CL
(gmlc `buildHub()` pattern) baked into `SbbIndexLoader` +
`AdminDashboardRegistry`.

**P1-8 Park/Resume primitive (L13).** Runtime-provided
`SuspendedRequest { park(budgetMs,onTimeout), complete(payload) }` over Vert.x
AsyncResponse + timer bridge + stickiness; replaces hand-rolled parks and the
single-daemon-thread NI park.

**P1-9 RA template kit (L15).** Abstract base implementing the 3-port wrapper:
config binding, lifecycle, idle sweeper auto-`endActivity`, metrics/KPI
counters (formalizes the `GmlcKpi` LongAdder+Micrometer-mirror pattern).

### 🟡 P2 — clustering, HA, Vert.x spine

**P2-1 Isolate Infinispan (L10).** Shade inside `jainslee-cluster` or own the
version property end-to-end so Quarkus BOM cannot drag 16 over 15.

**P2-2 Dual HA mode fabric (L12).** Productize STP's design in
`jainslee-cluster`: `ha.mode=active-active|active-standby` config-switched,
lease-generation fencing (`SctpEndpointLease`) generalized beyond SCTP to any
transport endpoint (TCP Diameter listen, SIP listen).

**P2-3 Full dialog restore for SIP/Diameter (L11).** Replicate the TCAP
export/import + missing-dialog-resolver pattern: `ra-sip-servlet` restores
dialog SM + CallSession profile; `ra-diameter` resumes session state.
No production-HA claim before kill-mid-call lab soak (elisa `nn-midcall-kill-1000`).

**P2-4 Cluster Topology View.** Monitor shows node→ASP/session ownership,
lease TTL, generation (prevents the GMLC "node 2 holds no SCTP = broken" confusion).

**P2-5 Vert.x transport spine (see ADR 0006).** Shared transport options,
direct event-loop→Disruptor dispatch, `runOnContext` command return,
WebSocket connection-id activities, io_uring opt-in.

### 🟢 P3 — ops/config

**P3-1 Runtime pool resize management API** (AutoReconfigEngine already
co/expands pools) to reduce BUILD_TIME repackage friction (L14).
**P3-2 Bake monitor-hub laws into defaults:** hub routing set,
`MonitorHandler(appName)` branding, readiness contract (401-anonymous ≠ ready),
`KpiCounterSet` utility.

## 3. Research findings → adaptations

| Source | Finding | Adaptation | Risk |
|--------|---------|------------|------|
| **BBQ** [R5] — block-based bounded queue (USENIX ATC'22) | Beats LMAX Disruptor up to 11.1× in their macro-benchmarks; native drop-old lossy mode | `QueueStrategy` abstraction on EventRouter: Disruptor stays default; BBQ-style backend opt-in; drop-oldest restricted to telemetry/trace paths — business events are never dropped | Low |
| **SEDA** [R1][R2] (Welsh/Culler/Brewer, SOSP'01; USITS'03 admission control) | Stages + explicit queues + dynamic controllers (pool sizing, batching, adaptive load shedding) keep p90 bounded under overload | Load-conditioning module behind flag: per-event-type queue-depth admission control, shedding order trace→metrics→business(never); complements reactive AutoReconfigEngine | Medium (flag off by default) |
| **Quarkus×Loom integration report** [R6] (DEBS'23, ACM DL) | The Loom–Netty thread "dance" allocates abundant intermediate structures → GC pressure; event-loop-carrier idea cuts switches | ADR 0006: fire events into the router directly from the Vert.x event loop; commands return to the originating context; measure allocation delta with JFR before/after | Medium (measure first) |
| **VT production war stories** [R7][R8] (Netflix/HikariCP/Caffeine; JFR docs) | Pinning (`synchronized` pre-JEP-491, native calls still) starves carriers; ThreadLocal retention at million-VT scale; JFR `jdk.VirtualThreadPinned` is the cheap detector | Pinning watchdog in jainslee-telemetry (alarm + metric on >20 ms pins); hot-path synchronized audit; ScopedValue guidance for `EventMdc`; keep `transitionTo` off-hot-path contract documented | Low (observability only) |
| **Netty io_uring vs epoll** [R10] (netty issues #152/#10622; 2026 benchmarks) | io_uring wins only IO-heavy + many connections/loop; wrong thread count loses ~8% to epoll | Per-RA `transport=epoll\|io_uring` config (default epoll) with automatic reduced event-loop sizing; benchmark harness gate before enabling on hosts | Low |
| **Generational ZGC / Shenandoah generational** [R11] (JEP 439; JEP 521 delivered in JDK 25) | Gen-ZGC <1 ms pauses at any heap but +15–30% RAM/+5–10% CPU; Shenandoah gen production-ready with lower overhead (compressed oops); G1 fine ≤12 GB | GC profiles in `dist/run.sh`: <4G→G1, 4–16G→Shenandoah generational, ≥16G latency-critical→Gen-ZGC; GC pause metrics feed AutoReconfigEngine conditions | Low |
| **AIEO** [R13] (arXiv 2510.04404, 2025) | Predictive scaling beats reactive under spikes | Roadmap: seasonal-EWMA EPS forecast pre-expands pools ahead of known peak hours | Low (later phase) |

Classic theory already embodied (do not regress): LMAX Disruptor paper [R4],
hashed/hierarchical timing wheels [R9] (Agrona wheel lineage),
Reactor pattern [R3] behind `SipTransport`-style interfaces,
coordinated-omission measurement discipline [R12],
SPSC ring comparisons [R14].

## 4. Non-regression protocol (applies to every item above)

```
[1] mvn test baseline BEFORE (record Tests run: per module — never trust exit 0 alone)
[2] Core changes sit behind interface/factory + flag whose default preserves old behavior
    (EventRouter default=disruptor; ProfileFacility default=in-memory; transport default=epoll)
[3] Existing public signatures unchanged — deprecate + delegate, never delete
[4] Each optimization ships one JMH harness + one test that has been seen red
[5] mvn test AFTER — same counts plus new tests; JFR allocation/pinning diff recorded
[6] Untouchable: GOAL 1–5 APIs already shipped, ADR 0001/0002 cache names, existing tests,
    zero framework deps in api/core, release=25
[7] Performance claims only after measurement on real hosts (prove-the-artifact law)
```

## 5. Roadmap

| Sprint | Content |
|--------|---------|
| A (near-zero risk) | P0-1..P0-4 fail-fast hardening + pinning watchdog + GC profiles + BBQ/drop-old skeleton for telemetry path |
| B | P1-1 SessionRegistry + P1-2 entity lifecycle + SEDA load-conditioning behind flag + P1-8 park primitive |
| C | P1-3 profile-jdbc pilot on OTA/ussdgw stores + P1-6 LinkStatusPort + P1-7 CL-merge SPI |
| D | P2 Vert.x spine (measure first) + io_uring opt-in + P2-1 shading |
| E | P2-2 dual HA fabric + P2-3 dialog restores + predictive scaling |

## 6. Done-when checklist

- [ ] Baseline `mvn test` green before AND after each merged item
- [ ] No throw-stub class remains in `jainslee-api` split packages
- [ ] Boot fails fast on `@InjectRa` name mismatch (with actionable error)
- [ ] RaCollector reflects live RA state without app-side feeding
- [ ] New modules (profile-jdbc, load-conditioning, vertx helpers) are opt-in and default-off
- [ ] Every perf claim carries a benchmark artifact from this repo

## 7. Research bibliography (citable)

Formal reference list for external presentations / papers. Each entry notes
which runtime subsystem it informed.

### Event dispatch & service architecture

- **[R1]** M. Welsh, D. Culler, E. Brewer.
  *"SEDA: An Architecture for Well-Conditioned, Scalable Internet Service."*
  Proc. 18th ACM Symposium on Operating Systems Principles (**SOSP '01**),
  Banff, Canada, Oct. 2001, pp. 230–243. DOI:
  [10.1145/502034.502057](https://doi.org/10.1145/502034.502057) ·
  [PDF](https://www.sosp.org/2001/papers/welsh.pdf)
  → load-conditioning / admission control design (P1, ADR 0005).
- **[R2]** M. Welsh, D. Culler.
  *"Adaptive Overload Control for Busy Internet Servers."*
  Proc. 4th USENIX Symposium on Internet Technologies and Systems
  (**USITS '03**), Seattle, WA, Mar. 2003.
  → percentile-bounded admission control target in the same module.
- **[R3]** D. C. Schmidt.
  *"Reactor: An Object Behavioral Pattern for Concurrent Event Demultiplexing
  and Event Handler Dispatching."* Pattern Languages of Program Design,
  Vol. 1, ACM Press/Addison-Wesley, 1995, pp. 529–545.
  → theoretical basis of the RA event-loop model and transport-swap seams.
- **[R4]** M. Thompson, D. Farley, M. Barker, P. Gee, A. Stewart.
  *"Disruptor: High performance alternative to bounded queues for exchanging
  data between concurrent threads."* LMAX Exchange technical paper, 2011 ·
  M. Fowler, *"LMAX Architecture,"* martinfowler.com/articles/lmax.html, 2011.
  → current `EventRouter` backbone (default queue strategy).
- **[R5]** J. Wang, D. Behrens, M. Fu, L. Oberhauser, J. Oberhauser, J. Lei,
  G. Chen, H. Härtig, H. Chen.
  *"BBQ: A Block-based Bounded Queue for Exchanging Data and Profiling."*
  Proc. 2022 USENIX Annual Technical Conference (**USENIX ATC '22**),
  Carlsbad, CA, July 2022.
  [PDF](https://www.usenix.org/system/files/atc22-wang-jiawei.pdf)
  → candidate alternative `QueueStrategy`; drop-old mode for trace paths.

### Virtual threads × event loops

- **[R6]** A. Navarro, J. Ponge, F. Le Mouël, C. Escoffier.
  *"Considerations for integrating virtual threads in a Java framework: a
  Quarkus example in a resource-constrained environment."*
  Proc. 17th ACM International Conference on Distributed and Event-Based
  Systems (**DEBS '23**), Neuchâtel, Switzerland, June 2023, pp. 103–114.
  DOI: [10.1145/3583678.3596895](https://doi.org/10.1145/3583678.3596895) ·
  open archive [hal-04112339](https://inria.hal.science/hal-04112339)
  → Vert.x spine dispatch policy (ADR 0006); documents the Loom–Netty GC cost.
- **[R7]** OpenJDK. *JEP 444: Virtual Threads* (JDK 21) ·
  *JEP 491: Synchronize Virtual Threads without Pinning* (JDK 24) ·
  JFR events (`jdk.VirtualThreadPinned`), Oracle docs.
  [openjdk.org/jeps/444](https://openjdk.org/jeps/444) ·
  [openjdk.org/jeps/491](https://openjdk.org/jeps/491)
  → VT pinning watchdog; entity-thread concurrency contract.
- **[R8]** *"Virtual Threads Two Years In: Production War Stories — the Pinning
  Edge Cases and What JDK 25 Fixed."* JavaCodeGeeks, May 2026
  (Netflix deadlocks; HikariCP carrier starvation; Caffeine ThreadLocal findings).
  → operational runbook input for telemetry alarms.

### Timers

- **[R9]** G. Varghese, T. Lauck.
  *"Hashed and Hierarchical Timing Wheels: Data Structures for the Efficient
  Implementation of a Timer Facility."* SOSP '87; reprinted
  IEEE/ACM Transactions on Networking 5(3), 1997.
  → lineage of the Agrona `DeadlineTimerWheel` default in `jainslee-scheduler`.

### Network transports

- **[R10]** Netty project — io_uring incubator transport:
  issue [#152](https://github.com/netty/netty-incubator-transport-io_uring/issues/152)
  *"netty io_uring was slower than epoll"* (2022, Cassandra workload);
  [netty/netty#10622](https://github.com/netty/netty/issues/10622) (2020);
  *"Does Netty's io_uring Make the 2× CPU Thread Rule Obsolete?"*
  besthub.dev, June 2026.
  → honest io_uring opt-in policy with reduced event-loop sizing.

### Runtime / GC

- **[R11]** OpenJDK. *JEP 439: Generational ZGC* (JDK 21; non-generational mode
  removed in JDK 24) · *JEP 521: Generational Shenandoah* — **Delivered,
  JDK 25** ([openjdk.org/jeps/521](https://openjdk.org/jeps/521)) ·
  Netflix Tech Blog *"Generational ZGC in Production"* ·
  G. Morling, *"Lower Java Tail Latencies with ZGC."*
  → per-size GC profiles for product dists.

### Measurement & adaptive orchestration

- **[R12]** G. Tene. *"How Not to Measure Latency."* 2013
  (coordinated omission). → mandatory methodology for every benchmark claim.
- **[R13]** *"Next-Generation Event-Driven Architectures: Performance,
  Scalability, and Intelligent Orchestration Across Messaging Frameworks."*
  arXiv:[2510.04404](https://arxiv.org/abs/2510.04404), Oct. 2025.
  → predictive-scaling roadmap for AutoReconfigEngine.
- **[R14]** Chronicle Software. *"Chronicle Ring vs LMAX Disruptor."*
  Oct. 2021 (SPSC sub-microsecond write-to-read latency comparison).
  → mechanical-sympathy context for future SPSC hot paths.

> **Citation note:** when presenting externally, cite as
> "micro-jainslee improvement program, docs/improvement-proposal.md §7" and
> keep DOIs intact. Findings were re-verified against primary sources
> (USENIX/ACM/OpenJDK/GitHub) on 2026-08-24.

