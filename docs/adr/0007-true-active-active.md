# ADR 0007 — True active/active: correlation, failover, and the hot path

- **Status:** **Implemented P0–P5; P6 lab proof NOT run** — no active/active claim is valid yet
- **Date:** 2026-09-29
- **Supersedes/extends:** [ADR 0001](0001-ss7-ra-nn-tcap-failover.md) (SS7 TCAP plane) ·
  [ADR 0002](0002-ra-sticky-ha-fabric.md) (RA sticky fabric) ·
  [ADR 0005](0005-event-router-load-conditioning.md) (router load) ·
  [ADR 0006](0006-vertx-transport-spine.md) (Vert.x spine)
- **Related code:** `jainslee-cluster`, `jainslee-core/EventRouter`,
  `jainslee-core/MicroSleeContainer`, `vendor-ras/ra-jss7`,
  `vendor-ras/ra-http-server`, jSS7 j25 `TCAPProviderImpl` / `DialogImpl`
- **Lab:** [`docs/lab/active-active-two-node.md`](../lab/active-active-two-node.md)

## Implementation status

- **Status line:** P0–P2 (correctness) implemented and tested on real JGroups
  members in one JVM, including a partition. **P6 lab not run — no
  active/active claim is valid yet.**

| Phase | Scope | Status |
|-------|-------|--------|
| **P0** | jSS7 M1–M4, M6 | **Shipped** — jSS7 `j25` `2204e8fb4`. Export/import 6/6 (3 new, real pending invoke + non-zero timeout) |
| **P0 / M7** | jSS7 inbound dialog router + foreign PDU inject | **Shipped** — jSS7 `j25` `4fbf6c669`. `TcapInboundDialogRouter`, `TcapForeignPdu`, `TCAPProvider.processForeignPdu` (same W2 mailbox as local traffic, never re-routed). 6/6 |
| **P1** | Make the cluster reachable | **Shipped** — `SbbEntityPool` seam + real router rebind, `start()` honours `clusterEnabled` with fail-fast, Quarkus cluster keys |
| **P2 / D11** | Cross-node response routing (R1) — symmetric topology | **Shipped** — `Ss7InboundDialogRouter` (DTID → owner from replicated OTID ranges, overlap refuses to start, absent owner → takeover path), `ClusterUnicast` (ISPN `ClusterExecutor`, one unicast, no cache), `SccpAddressCodec`. Cluster mode without an OTID range fails `raActive()`. The older per-RA / container event forward (§0.1 A–C) is bypassed for SS7 when the router runs |
| **P3 / D3 / D12** | Leases, fence, takeover, witness | **Shipped** — claim-before-send (`activity:<id>`), per-TCAP-transaction lease (`otid:<n>`), `Ss7TransmitFence` before every send, lazy takeover only when the owner left the view (`RaDialogLeaseCaches.takeOver`, CAS gen+1, guard on the resolver), scheduled heartbeat with local liveness, orphan GC that never assigns, fenced caches (`DENY_READ_WRITES` + generation merge policy + await state transfer), `ClusterWitness`. Refusals reach the SBB (`Ss7MapEvent.Dialog(REJECT, "local-refusal: …")`) |
| **P4** | Performance | **Not in this round** — measure first (JMH), then shard / ASYNC_COMMIT |
| **P5** | Observability | **Partially shipped** — node labels, ring-lag, latch-await, delivery-timeout; new counters `ss7_tcap_foreign_pdu_*`, lease `fenceBlocked / takeovers* / staleReleased / orphansCollected`. Flow id + real Counter/Timer instruments still open |
| **P6** | Lab proof | **Not run** |

### Tests that prove P0–P3 (not a claim about a deployment)

| Test | What it proves |
|------|----------------|
| jSS7 `TcapInboundDialogRouterTest` (6) | CONTINUE/END on a non-owner processed by the owner; first-CONTINUE remote address survives; no ping-pong; declined route → resolver; throwing router degrades |
| `ClusterUnicastTest` (5) | delivery to exactly one member; exact node-name match (`ss7-1` ≠ `ss7-10`) |
| `Ss7InboundDialogRouterTest` (8, 3 members) | PDU forwarded intact; own / unknown DTID not routed; dead owner → takeover path; stopped RA → local re-process; overlapping range refuses to start (this caught the R3 empty-replica bug) |
| `RaDialogLeaseFenceTest` (5) | second claim refused; crash takeover gen+1; **scenario C in-process**: partitioned owner fenced, majority takes over, merge keeps the newer lease; **two nodes without witness: nobody takes over**; heartbeat + GC never assigns. Mutation-checked: with `ALLOW_READ_WRITES` the partition tests fail |
| `Ss7TransmitFenceTest` (3) | retry of the same activity on another node does not send a 2nd BEGIN; partitioned owner fenced at the RA send path |
| `TakeoverSnapshotFidelityTest` (3) | full GT, wall-clock deadline and pending invokes survive the cluster hop (P, Q) |

Totals after this round (JDK 25): core 490 · cluster 106 (4 skipped) · telemetry 49 ·
ra-jss7 112 · api 4 · scheduler 13 · ra-spi 41 · admin-spi 7 — all green. Consumers of
the changed cluster APIs (ms-ispn, ra-http-*, ra-grpc-*, ra-diameter) green;
`ra-sip-servlet` `SipRaDialogLifecycleTest.byeKeepsDialogUntilFinalResponseThenEnds`
fails independently of this work (the test never configures an outbound sender, so the
200 BYE is dropped).

### What is deliberately still open

Tracked with priorities in [`docs/pending-issues.md`](../pending-issues.md).

| Gap | Why it matters |
|-----|----------------|
| **P6 lab** | Everything above is in-process. No claim before a recorded run of scenario C on real hosts with a witness |
| **App correlation after takeover** | The MAP helpers' `appDialogId ↔ OTID` maps and MAP dialog state (M5) are node-local. After a takeover the survivor publishes on the OTID, not the app id; the SBB/HTTP side of the dead node is gone anyway (scenario F), so the client retries with its `activityId` |
| **Timers / entity ids** | Node id and unique per-SBB timer ids fixed (D6 partial); entity ids get a `<node>:` prefix in cluster mode (D1). Deadline replication not done — a T-timer armed on a dead node dies with it |
| **Single disruptor worker + 30 s latch** | Measured, not fixed. Holds the router for `onEvent` duration (see correction below) |
| **Off-heap bytes absent from `SbbEntitySnapshot`** | `@OffHeap` SBBs remain non-failoverable |
| **Reflection in `jainslee-core` cluster seams (O)** | Unchanged; a `ClusterPort` SPI is the fix |
| **Container ingress (A)** | Still requires a `SleeEvent` payload; unused by SS7 under D11. Envelope leak (K) fixed with a lifespan |
| **No benchmark numbers** | Every D7 target is a projection |
| **Per-node app state** | Rate limiters / caches are N× under active/active (outside this repo) |

### 0.1 Review 2026-09-30 — defects found in the shipped code

Static read, confirmed against the tree. Baseline before any fix (JDK 25):
core 490 · cluster 96 (4 skipped) · telemetry 49 · ra-jss7 98 · api 4 ·
scheduler 13 · ra-spi 41 · admin-spi 7 — all green. None of these tests
exercises two nodes, which is why A–D were not caught.

| # | Defect | Where | Effect |
|---|--------|-------|--------|
| A | Container ingress requires `payload instanceof SleeEvent`, but no SS7 event is `Serializable` and `MapEventPayload` is not a `SleeEvent` | `MicroSleeContainer.deliverIngressEvent` | every forwarded SS7 response is dropped |
| B | Nothing calls `forwardEventToOwner`; ra-jss7 still forwards on the per-RA bus | `Ss7ResourceAdaptor.tryForwardToActivityOwner` | split topology strands the response |
| C | Forward target is the SS7 dialog owner, which on the SS7 node is itself | `lookupRemoteOwnerNodeId` | never forwards to the HTTP node |
| D | A node without `ra-jss7` has no command port; command forward is OFF | split topology | scenario A cannot start |
| E | Ownership is claimed **after** the transmit | `Ss7ResourceAdaptor.afterLocalOutbound` | a retried activity id on two nodes → two BEGINs on the wire |
| F | `heartbeat()` is never scheduled | `RaDialogLeaseCaches.startSweeper` | once wired, a live node's idle dialogs expire and are stolen |
| G | The reaper hands an expired lease to whichever node sweeps first, even one without the TCAP dialog | `RaDialogLeaseCaches.reclaimExpired` | ownership points at a node that cannot serve the dialog |
| H | Owner refresh is a blind `put`, not a CAS | `Ss7DialogOwnershipTracker.onDialogTouched` | a zombie overwrites the new owner — the fence is void |
| I | Remote owners are cached locally forever | both trackers' `lookupOwner` | D3 finding, still open |
| J | No partition handling on any cache (Infinispan default `ALLOW_READ_WRITES`) | `ClusterManager.getCache` | a partitioned node reads its stale replica and keeps sending — scenario C cannot pass |
| K | Ingress envelopes have no lifespan; only the target removes them | `ContainerStickyEventIngress.forward` | envelopes to a dead node leak |
| L | Sticky `REJECT` only logs | `Ss7ResourceAdaptor.sendOutbound` | the SBB never learns; activity/HTTP park hangs to timeout |
| M | Snapshot export parses the OTID out of the app dialog id | `exportSnapshotBestEffort` | app-named outbound dialogs (`gmlc-…`) are never snapshotted |
| O | Reflection in `jainslee-core` for the cluster seams | `MicroSleeContainer` | violates the core no-reflection rule |
| P | Snapshot addresses kept GT digits only | `Jss7TcapDialogFailoverPort.toPortable` | after takeover the survivor answers on a different GT translation — **fixed** (`SccpAddressCodec`) |
| Q | ra-jss7 never carried jSS7 M1/M2: `idleDeadlineNanos` passed where epoch ms is expected, no `PendingInvoke[]` | `toPayload` / `toJss7Snapshot` | M1/M2 had no effect on the cluster path; every in-flight dialog refused — **fixed** |
| R3 | Every cache `awaitInitialTransfer(false)` | `ClusterManager.getCache` | a joining node reads an empty replica (found by the overlap test) — **fixed for fence caches** |
| S | `isNodePresent` used `contains` | `ClusterManager` | `ss7-1` reported present while only `ss7-10` was — **fixed** |
| T | Timer owner id = `System.identityHashCode` | `SleeTimerSchedulerBridge` | not unique: `cancelAll` could cancel another SBB's timers — **fixed** |

**Correction to §1.8.** The latch does **not** hold the router for a MAP round
trip: `sendCommand` is asynchronous and the SBB returns immediately. It holds
the router for the duration of `onEvent`. The real costs are the per-event
router→VT→router handoff (throughput ceiling) and head-of-line blocking behind
any SBB that does blocking I/O. Measure before fixing (P4).

### 0.2 Decisions taken 2026-09-30

- **D11 — symmetric topology.** Every node runs the full RA set
  (`ra-http-server` **and** `ra-jss7`), one ASP each under the same AS
  (lab prerequisites 3 and 4 already assumed this). An SBB always sends through
  its **own** node's SS7 stack, so the TCAP dialog owner and the HTTP owner are
  the same node. A CONTINUE/END/ABORT that the STP delivers to another node is
  forwarded as the **raw SCCP/TCAP PDU** to the owner, chosen by DTID from the
  disjoint OTID ranges. The owner decodes it with its own stack — no decoded
  `MAPMessage` ever crosses the cluster, and no takeover happens while the owner
  is alive. Requires jSS7 **M7** (inbound dialog router hook + foreign-PDU inject).
- **D12 — a third, witness node.** Two nodes cannot be both safe and available
  under partition. A lightweight third JGroups member (cluster module only, no
  RAs) provides the majority; lease caches use `DENY_READ_WRITES`, so the
  minority side stops transmitting instead of guessing.
- **Scope now:** P0 → P2 (correctness) before P4 (performance).

Implemented as described; see the status table. Two implementation choices:

- **Two lease keys.** `activity:<appId>` is idempotency (one BEGIN per activity,
  cluster-wide); `otid:<n>` is ownership of the TCAP transaction (fence +
  takeover). Inbound dialogs have only the second.
- **Takeover needs the owner absent from the view, not an expired timestamp.**
  Expiry is written with the owner's clock; comparing it with ours makes ownership
  depend on clock skew. The view is authoritative because the fenced cache denies
  the minority side.

---

## 0. The scenario this ADR must satisfy

Two micro-jainslee JVM nodes, both active/active, behind one load balancer.

```text
                      ┌─────────────────────────── LB ───────────────────────────┐
   client ──HTTP──▶  │  node-1                          node-2              │
                      │  ra-http-server  :8088          ra-jss7 (M3UA/MAP)  │
                      │  GatewaySbb                    MapSriSbb            │
                      └───────┬───────────────────────────────┬───────────────┘
                              │ Ss7Command.MapSendRouting…    │ SCTP → STP
                              │ dialogId = gmlc-<uuid>        │
                              ▼                               ▼
                        node-1 owns the TCP socket    node-2 owns the TCAP dialog
                        (Vert.x HttpServerResponse     (jSS7 NonBlockingHashMap)
                         in pendingResponses —         SS7 stack node-2
                         CANNOT be written by node-2)
```

Two hard requirements, then one soft one:

1. **R1 — the response must reach the HTTP connection on node-1**, no matter
   which node receives the TCAP CONTINUE.
2. **R2 — if a node dies mid-flow, the surviving nodes must continue the flow.**
3. **R3 — a recovered node rejoins active/active without operator action.**

---

## 1. Root-cause findings (static read, not yet proven by lab)

These are the facts that make the above scenario fail today. Each one is a
named defect, not a tuning knob.

### 1.1 The cluster module is reachable but unwired

| Fact | Evidence |
|------|----------|
| `MicroSleeContainer.start()` never reads `configuration.isClusterEnabled()` | it only calls `invokeStartOnClusterManager(this.clusterManager)`, which no-ops on `null` |
| `bindCluster(Object)` / `bindDistributedSbbPool(Object)` are called **only from tests** | repo-wide grep: `ClusterManagerTest`, `DistributedSbbEntityPoolTest` — both reflectively |
| The only production `new ClusterManager(...)` sites are two MS-plane examples | `example-quarkus-ms/MsQuarkusBootstrap`, `example-ms-two-service/TwoServiceMain`; **neither calls `bindCluster`** |
| Result | `bindRaHaSeams(ra)` early-returns on `cm == null`, so **no RA ever receives `setClusterManager`** unless an embedder injects the bean by hand |
| `adapter-quarkus` has no `clusterEnabled` / `nodeId` config keys | `MicroJainsleeBuildConfig` maps only buffer-size, VT preference, pool min/max, delivery mode |

`ota.cluster.enabled` and `ussd` both resolve to `false`; USSDGW's
`ClusterBootstrap` is a deliberate stub returning `null`.

### 1.2 `bindDistributedSbbPool` is a dead seam — the snapshot path is unreachable

`VirtualThreadSbbEntityPool` is declared `final`, so `DistributedSbbEntityPool`
(composition, not inheritance) cannot replace it. The container records the
reference and does nothing else; `EventRouter` keeps using the local pool.
The container Javadoc says as much and then tells embedders to call
`EventRouter#bindSbbEntityPool(VirtualThreadSbbEntityPool)` — which is
impossible, because the cluster pool is not a subtype.

Consequence: `sbb-entity-state` is written by nobody and read by nobody. The
cross-node hydrate path in `DistributedSbbEntityPool.acquire` is dead code.

### 1.3 `@OffHeap` state is structurally unreachable across nodes

`SbbEntitySnapshot` documents it outright: *"OffHeap CMP is never included —
heap `@CmpField` only."* Off-heap slot addresses are
`arenaBase + slotIdx * slotSize` and arenas are node-local `DirectByteBuffer` /
`MappedByteBuffer`. There is no serialize path for a slot's bytes.

So any SBB that opts into `@OffHeap` for throughput is **automatically
non-failoverable**. That is a direct active/active vs. high-performance
conflict and must be resolved explicitly, not discovered in production.

### 1.4 Entity ids are minted per-JVM — they collide across nodes

`EntityIdAllocator` is a plain `AtomicLong` starting at 1. `ContainerBackedIesPool.allocateNew`
produces `"<SimpleName>#<n>"`. Two nodes mint the same ids. `sbbs` is a
per-JVM `ConcurrentHashMap<String, SimpleSbbLocalObject>` keyed by that id.

Meanwhile the *only* cluster-meaningful key in the whole model is the
activity-context name, which for the 3-port path is the RA-chosen handle id —
`dialogId` for SS7, `sessionId` for HTTP, `Call-ID` for SIP. Everything else is
node-local.

### 1.5 There is no inbound cross-node event path at all

`RaStickyCommandBus` forwards **outbound commands** to a remote owner. There is
no equivalent for **inbound protocol responses**. When node-2's `ra-jss7`
receives a CONTINUE for an activity whose HTTP connection lives on node-1,
`Ss7ResourceAdaptor.publish` does:

```java
bootstrap.fireEvent(event, s.activityHandle, null);
```

→ `SleeEndpointPortImpl.fireEvent` → `acnf.lookup(handle.getId())` → **the ACNF
on node-2 has no local binding** → `IllegalStateException: Unknown activity
handle`. Even with a clustered ACNF, `NamedActivityContext` carries the name
only; no node executes the event.

And if the sticky router is consulted at all, `RaHaSupport.decide` rewrites
`FORWARD_REMOTE` → `REJECT` because `jainslee.ra.sticky.forward` defaults to
`false`. `RaHaSupportTest.syncPathDefaultRejectsRemoteOwnerWithoutForward`
pins that as a test invariant.

### 1.6 Dialog ownership is permanent — no lease, no reclaim

`RaDialogOwner` = `(dialogId, ownerNodeId, raName, generation, updatedAtEpochMs)`.
No TTL, no `lifespan`, no `maxIdle`, no reaper. If node-2 dies holding
`ra-dialog-owner[gmlc-x]`, every other node resolves
`FORWARD_REMOTE → REJECT` **forever**.

The one working reclaim in the whole module is
`SctpEndpointFailoverCoordinator.reclaimOrphanedEndpoints()`, which does the
right thing: an Infinispan `@ViewChanged` listener, `isNodePresent(owner)`,
then a CAS with `generation + 1`. That pattern should be generalized.

`generation` is also not enforced on the send path — `StickyRaCommandRouter.decide`
only string-compares `ownerNodeId`. A partitioned-but-alive zombie can still
transmit.

### 1.7 Timers cannot be replicated

`SleeTimerSchedulerBridge`:

```java
private static final String LOCAL_NODE_ID = "micro-jainslee";   // same on every node
...
long dialogId = System.identityHashCode(sbbLocalObject);          // JVM identity, not a dialog
```

`TimerRecord` already carries `nodeId` and is `Serializable`, but nothing ever
writes it anywhere. A T7/T9 armed on node-2 dies with node-2, and `stop()`
calls `wheel.clear()`.

### 1.8 The single `disruptor-worker` blocks on the 30-second latch

`EventRouter` registers **one** `EventHandler` on a 2048-slot ring with
`BlockingWaitStrategy`. `deliverEvent` in the default `SYNC` mode:

```java
entity.submit(...);              // onto the entity's own virtual thread
if (!done.await(30, TimeUnit.SECONDS)) { ... }
```

The **router's only worker thread** parks for the whole SBB execution. A MAP
`sendRoutingInfoForSM` round trip of 300–1500 ms blocks the entire container.
Worse, `InMemoryActivityContext.transactionLock` is taken *before* the await and
released *after*, so all events on the same activity serialize too.

Order-of-magnitude ceiling: **~30–100k events/s** with no observer bound,
**~3–10k events/s** with the telemetry observer bound (the p99 target in the
disabled `EventRoutingBenchmark` is 5M/s — currently 2–3 orders of magnitude away).

Per-event hot-path cost that is pure overhead today:

- `EventMdc` — **15 `ThreadContext` put/remove per delivery**, unconditional
- `event.getClass().getSimpleName()` × 2 — `String` allocation per event
- `new ArrayList<>(getAttachedSbbs())` where `getAttachedSbbs()` itself copies
  a `CopyOnWriteArrayList` → 2 arrays + a wrapper, then `Collections.sort` (TimSort)
- 3 CHM `computeIfAbsent` (collector / spunk / stale) per delivery
- `new AtomicReference<>()`, `new CountDownLatch(1)`, 2 lambdas
- `RaFanInGateway` exists with batch draining and is **never bound** by the container

### 1.9 Profiler cannot see a distributed flow

- `DispatchObserver.onEventProcessed(sbbType, entityId, latencyNs)` — no
  request id, no hop, no node
- `EventMdc.KEY_NODE_ID` is the literal string `"local"`
- every Micrometer gauge uses `Tags.empty()` → two nodes produce identical series
- **no `Timer`/`Histogram`/`Counter` instruments at all** — only `Gauge`
  over cumulative sums, so Prometheus must compute every `rate()`
- `jainslee_gc_count` / `jainslee_gc_time_ms` are hardcoded `0`
- p99 exists only in the `/api/telemetry/snapshot` JSON, computed by cloning
  and sorting a 100-element `long[]` on **every read**, behind a non-atomic
  `volatile int` index → it loses samples under concurrency
- `ringBuffer.remainingCapacity()` is never called; ring lag is invisible
- the 30 s `await` has no counter — a 29 s MAP round trip is indistinguishable
  from a fast one
- `RaHaMetrics` (`sticky_reject`, `sticky_forward`, `owner_claim`, …) is a
  `Map<String,Long>` returned by `snapshot()` and is **not registered as gauges**

### 1.10 jSS7: dialogs are movable, in-flight invokes are not

Read of `coral-valley/jSS7` (j25), static:

**Good news.** `TCAPProviderImpl.dialogs` is a single flat
`NonBlockingHashMap<Long, DialogImpl>` per SSN with **no ASP coupling**. N ASPs
under one AS already share one table inside a JVM. `TcapDialogSnapshot`,
`exportDialog`/`importDialog`, and `TcapMissingDialogResolver` all exist and
work. The OTID is on the wire and is a sufficient correlation handle, so **no
new TC-BEGIN and no protocol change is required**.

**Blockers.**

| # | Blocker | Fix |
|---|---------|-----|
| **M1** | `operationsSent[]` holds live `InvokeImpl` objects that the snapshot does not serialize. A `ReturnResult(Last)` for a taken-over dialog hits `processOperationsState` → `addReject(UnrecognizedInvokeID)` **sent to the real SMSC**, upstream of MAP's rehydrate | export `PendingInvoke[]{invokeId, invokeClass, operationCode, timeoutMs, remainingNanos}`; add `DialogImpl.restorePendingInvoke` |
| **M2** | `TcapDialogSnapshot.idleDeadlineNanos` is `System.nanoTime()` — meaningless across JVMs; a takeover silently grants a fresh 60 s window | send absolute epoch ms; re-arm `nanoTime()` locally on import |
| **M3** | Only CONTINUE consults the resolver. TC-END and TC-ABORT do a bare `dialogs.get(id)` → a taken-over dialog **leaks for the full idle timeout** | route END/ABORT misses through `tryImportMissingDialog` too |
| **M4** | `hasPendingInvokes()` is asserted by ADR 0001 but `Dialog` has **no accessor** for outstanding operations | add `Dialog#getPendingInvokeCount()`; resolver returns `null` → clean `UnrecognizedTxID` P-Abort instead of a corrupt REJECT |
| **M5** | MAP state (`origReference`, `destReference`, ERI, extContainer) is not snapshotted | survivable for single-op SRI; fatal for ATI/`sendRoamingNumber` two-step |

Corroborating signal: all three tests in `TcapDialogExportImportTest` call
`setInvokeTimeout(0)` in `setUp`. **The invoke-timer path was never exercised.**

---

## 2. Decision — the three planes

The scenario fails today because three concerns are conflated in one hop.
Separate them, and each becomes tractable.

```text
  PLANE 1 — TRANSPORT TERMINATION          node-local, TCP-affine, NOT replicable
  ─────────────────────────────────────────────────────────────────────────────
  HTTP socket, SCTP association, M3UA ASP, SIP dialog.
  A response can only be written by the node holding the connection. Ever.

  PLANE 2 — PROTOCOL DIALOG                replicable, requires M1–M4 in jSS7
  ─────────────────────────────────────────────────────────────────────────────
  TCAP dialog + OTID + invoke table + MAP state + timers.
  Must be exportable/importable and must have a lease so it can be reclaimed.

  PLANE 3 — SBB EXECUTION                  follows Plane 2's owner
  ─────────────────────────────────────────────────────────────────────────────
  Entity id derived from the activity id. State replicated. Routed to the
  node that owns Plane 2, never to the node that accepted the socket.
```

### D1 — Correlation is the activity handle id, end to end, and nothing else

`RaBootstrapPort.fireEvent`'s `Address` parameter is **accepted and dropped** —
`BootstrapPortAdapter.fireEvent` never reads it. Remove it from the internal
routing decision; address by `ActivityHandle.getId()`.

One id, minted at ingress, carried through every hop:

```text
HTTP request  ──▶  activityId = "gmlc-<uuidv7>"     (client-supplied, or minted)
                     │  node-1 ACNF binding         (node-local, plane 1)
                     ├──▶ SBB entityId = activityId (NOT "<Type>#<AtomicLong>")
                     ├──▶ Ss7Command.dialogId = activityId
                     │      → jSS7 OTID assigned from this node's range
                     └──▶ RaDialogOwner[dialogId=activityId] → ownerNodeId
```

Rules:

- Entity ids become `activityId`-derived, or a **cluster-wide** `EntityIdAllocator`
  (ISPN `AtomicLong` scoped per node). Kill the per-JVM `AtomicLong`.
- The 3-port path is **mandatory**; the legacy `RaBootstrapContextImpl` path
  mints `entityName + ":ach:" + n` per JVM and is not cluster-stable. Add a
  startup warning if a legacy `ResourceAdaptorContext` is used with `clusterEnabled=true`.
- `InfinispanProfileStore` is a **single-node file store (SIFS)**, not a
  replicated cache. It cannot be the durable home for active/active state.
  Either a real replicated cache or the app's own Postgres.

### D2 — New: `RaStickyEventBus` — the missing fifth piece

This is what makes R1 work, and it is small and protocol-agnostic.

Today the fabric forwards **commands out**. Add the mirror: forward **events in**.

```java
// jainslee-cluster
public final class RaStickyEventBus {
    // cache: ra-<name>-event (DIST_SYNC), key = envelopeId, value = StickyEventEnvelope
    // StickyEventEnvelope { envelopeId, targetNodeId, sourceNodeId,
    //                       activityId, String eventType, byte[] payload,
    //                       long createdAtEpochMs }

    public boolean route(String activityId, RaDialogOwner owner,
                         RaStickyRouter.Decision decision, Serializable event);
}
```

On the receiving node, a `@CacheEntryCreated` listener re-injects the event
into the **local** `RaBootstrapPort.fireEvent` path — so `acnf.lookup(activityId)`
now hits a real local binding and the SBB runs on the node that owns the
socket.

Change one default, deliberately and loudly:

```java
// RaHaSupport: default flips false → true for INBOUND event routing only
jainslee.ra.sticky.forward=true      // commands: unchanged, still sync-path REJECT
jainslee.ra.sticky.forward.events=true   // NEW — inbound cross-node event routing
```

Keep the command-side default `false`. Forwarding a *request* to a remote node
is a protocol change; forwarding a *response* to the node that already owns the
client's TCP connection is just routing. `RaHaSupportTest` must be updated with
a matching test pinning the new asymmetry.

Because the payload must survive the wire, the event needs a portable form.
`Ss7MapEvent.Service` holds a `MAPMessage` — an `org.restcomm.*` type, which is
**not in `MarshallingAllowList`**. Two options:

- **(a)** Introduce `MapEventPayload` — a Serializable POJO mirroring the decoded
  MAP response (the same pattern already used for `TcapDialogSnapshotPayload`).
  Works with today's Java-serialization marshaller. **Recommended.**
- **(b)** Register `org.restcomm.*` in the allow-list. Rejected: the allow-list
  is a deliberate blast-radius fence, and jSS7 types are not `Serializable`
  across versions.

### D3 — Ownership becomes a **lease**, and reclaim follows the SCTP pattern

Generalize the one thing that already works.
`SctpEndpointFailoverCoordinator.reclaimOrphanedEndpoints()` is the template:
`@ViewChanged` → `isNodePresent(owner)` → CAS `generation + 1` → rebind.

```java
// RaDialogOwner gains:
private final long leaseExpiresAtEpochMs;
private final long ownerBootEpochMs;    // fencing: bumped on every restart
```

- `onTouched` renews `leaseExpiresAtEpochMs`; a heartbeat renews leases for
  dialogs with no traffic (a 60 s-idle TCAP dialog is normal and must not expire).
- A `DialogLeaseReaper` on `@ViewChanged` **plus** a periodic sweep: for each
  expired lease whose owner is not `isNodePresent`, CAS to a new owner with
  `generation + 1` **and** `ownerBootEpochMs` from the current incarnation.
- **Enforce `generation` on the send path.** `StickyRaCommandRouter.decide`
  must return the generation it read, and the transmit must CAS-verify it
  immediately before writing to the wire. A zombie that lost its lease cannot
  send.
- `lookupOwner` currently caches remote hits into `localOwners` forever
  (`localOwners.put(activityId, remote)`). That turns a transient remote read
  into a permanent local belief. Only cache **local** ownership locally;
  remote ownership goes back to ISPN every time (it is one `REPL_SYNC` get).

### D4 — Fix the entity pool so replication is actually reachable

Replace the dead seam with a real interface in `jainslee-core`:

```java
// jainslee-core — new
public interface SbbEntityPool {
    SbbEntity acquire(String sbbId, Supplier<Sbb> factory);
    void release(SbbEntity entity);
    SbbEntity findEntity(String sbbId);
    int size();
    // ... whatever EventRouter actually calls
}
```

- `VirtualThreadSbbEntityPool implements SbbEntityPool` — **drop `final`**.
- `EventRouter` holds `SbbEntityPool`, not the concrete class.
- `DistributedSbbEntityPool implements SbbEntityPool`, wrapping a delegate.
- `bindDistributedSbbPool` now **actually rebinds** `EventRouter`.
- `bindSbbEntityPool` overload keeps working for existing embedders.

This is the single highest-leverage change: it is what turns `sbb-entity-state`
from a write-only cache into the substrate for R2.

### D5 — Snapshot off-heap bytes, or forbid `@OffHeap` under cluster

Explicit, because it is a genuine perf-vs-HA conflict.

| Option | Cost | Verdict |
|--------|------|---------|
| Add `byte[] offHeapSlotBytes` to `SbbEntitySnapshot` (copy the slot out) | one `max(slotSize)` copy per checkpoint, debounced 50 ms | **accepted** — off-heap arena stays a local cache, cluster snapshot becomes the durable truth |
| Forbid `@OffHeap` when `clusterEnabled` (fail-fast at `start()`) | removes the option | **also implement** as a config guard so nobody discovers it in production |
| Off-heap only for derived/cacheable fields; canonical state in heap `@CmpField` | discipline, no runtime cost | **the documented pattern** — prefer this |

Also fix `SbbEntitySnapshot.generation`: it is a **node-local** `AtomicLong`
never read back on hydrate, so it cannot arbitrate anything. Write it back with
a CAS so LWW is at least decidable. Or better, keep the generation authority in
`RaDialogOwner` and let the snapshot carry only payload.

### D6 — Replicated timers, or deadlines only

- `LOCAL_NODE_ID = "micro-jainslee"` on every node is a bug waiting to happen.
  Set it from `ClusterManager.getNodeId()`.
- `dialogId = System.identityHashCode(sbbLocalObject)` must become the
  SBB entity id — an identity hash is not stable across a restart.
- **Do not** replicate timer *handles*. Replicate **absolute deadlines** and
  let every node arm a local timer, with the owning node's lease deciding who
  actually fires the event. Coalesce on the ISPN `putIfAbsent` so only one
  node wins. Wake-ups at a fixed cadence, firing is idempotent and guarded by
  ownership — that is what makes timers survivable without a leader.

### D7 — Performance: remove the router as the bottleneck

Ordered by ratio of gain to blast radius.

**P-a — Shard the disruptor by activity context.** The `transactionLock` is
already per-activity, so sharding by `aciName.hashCode()` preserves exactly the
ordering that matters and removes cross-activity head-of-line blocking.

```java
int shards = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
for (int i = 0; i < shards; i++) {
    int shard = i;
    disruptor.handleEventsWith(new EventHandler<EventWrapper>() {
        public void onEvent(EventWrapper w, long seq, boolean endOfBatch) {
            if (Math.floorMod(w.aci.getActivityContextName().hashCode(), shards) != shard) return;
            dispatch(w.event, w.aci);
        }
    });
}
```

This is a behavioural change: the single-consumer ordering guarantee across
*unrelated* activities goes away. Events on the **same** activity keep total
order. Ship it behind `microjainslee.event-router.shards=N` (default 1 = today's
behaviour, byte for byte), then default it to N once the lab proves it.

**P-b — Make the SBB never block on a protocol round trip.** The single correct
pattern, already used by `MapSriSbb`:

```java
// onEvent: send the command, return. The response arrives as a NEW event.
public void onEvent(SleeEvent ev, ActivityContextInterface aci) {
    ss7.sendCommand(new Ss7Command.MapSendRoutingInfoForSm(activityId, ...));
    return;                                   // router thread released here
}
// later, on the same entity's virtual thread:
public void onEvent(Ss7MapEvent.Service svc, ActivityContextInterface aci) { ... }
```

with `EventDeliveryMode.ASYNC_COMMIT` (already implemented — it drops the latch
entirely and commits on the entity thread). Default it for MAP-shaped SBBs.

**P-c — Bind `RaFanInGateway`.** Batch the publishes, not the deliveries. The
container never binds it today; one line in the constructor.

**P-d — Make `EventMdc` conditional.**

```java
private static final boolean MDC_ON =
        log4jLevelEnabledFor(Debug.class);   // or a system property
```

15 `ThreadContext` operations per event is the single most expensive
always-on line in the router. Off by default at INFO.

**P-e — Cache `getSimpleName()`.** Two `String` allocations per event.
`ClassValue<String>` or a static `ConcurrentHashMap<Class<?>,String>`.

**P-f — Cache the sorted attachment list.** `getAttachedSbbs()` already copies
a `CopyOnWriteArrayList` into a new `ArrayList` inside `unmodifiableList`, and
`dispatch` copies *that* again and runs `Collections.sort`. Attachments change
rarely — sort once, invalidate on attach/detach.

**P-g — One-time, not per-event, collector registration.** The three
`computeIfAbsent` calls per delivery become a single
`ConcurrentHashMap<Class<?>, Collector>` resolved at dispatch entry.

**P-h — Disable the 30 s await for `ASYNC_COMMIT`.** It is already bypassed;
make sure `SYNC` never silently becomes the default again, and count timeouts.

**Stated targets** (to be proven with JMH, per the prove-the-artifact law):

| Path | Today | Target |
|------|-------|--------|
| 1→1, no observer | 30–100k ev/s | ≥ 500k ev/s |
| 1→1, telemetry bound | 3–10k ev/s | ≥ 200k ev/s |
| p99 delivery latency | not exported | < 50 µs, exported |
| MAP round trip (SRI) | blocks the whole container | zero router occupancy while in flight |

### D8 — Make the profiler able to see a distributed flow

- **Correlation id in the event contract.** `EventWrapper` is a 2-field carrier.
  Add `flowId` (the activity id) and `hop` so a 2-node flow is one trace.
- **Node label on every gauge.** `Tags.of("node", clusterManager.getNodeId())`.
  Two nodes behind one Prometheus are currently indistinguishable series.
- **Real instruments.** Replace `Gauge` over cumulative sums with
  `Counter` / `Timer` / `DistributionSummary`. Prometheus gets `rate()` and
  `histogram_quantile()` for free instead of computing them client-side.
- **Fix the p99 ring.** 100 slots, `volatile int` index, `clone + sort` per read.
  Move to a `LongHistogram` (HdrHistogram) — lock-free writer, accurate read.
- **New metrics that map to R1/R2/R3 directly:**

  | Metric | Meaning |
  |--------|---------|
  | `jainslee_event_ring_lag_slots` | `bufferSize - remainingCapacity()` — the missing overload signal |
  | `jainslee_delivery_latch_await_seconds` | Timer. **A 29 s MAP round trip is currently invisible.** |
  | `jainslee_delivery_timeout_total` | The 30 s path firing |
  | `jainslee_sticky_event_forward_total{reason}` | R1 working or not |
  | `jainslee_sticky_event_reject_total{reason}` | R1 broken, with the reason |
  | `jainslee_dialog_lease_expired_total` | R2 reclaim fired |
  | `jainslee_dialog_takeover_total{outcome}` | take-over success / refused-pending-invoke |
  | `jainslee_sbb_hydrate_total{source=cluster\|local}` | R2/R3 — how much state came from ISPN |
  | `jainslee_node_view_members` | cluster membership |

- **Export `RaHaMetrics` as gauges.** They already exist as counters; nobody
  registers them.
- **Real GC counters** instead of the hardcoded `0`s — `GarbageCollectorMXBean`
  is not a framework dependency and `jainslee-core` has no excuse here.
- **Wire `firedTimers` / `cancelledTimers` / `pendingTimers`** from
  `AgronaTimerWheelFacade` — they exist and are called from nothing.
- **Enable the disabled benchmark.** `EventRoutingBenchmark` is `@Ignore`
  pending JMH. Do that first; every claim in D7 without a number is a guess.
- **Pinned-virtual-thread watchdog.** `tracePinnedThreads` is flipped but
  nothing consumes JFR `jdk.VirtualThreadPinned` events.

### D9 — Quarkus adapter must be able to turn the cluster on

Add to `MicroJainsleeBuildConfig` (and Spring / JakartaEE equivalents):

```properties
microjainslee.cluster.enabled=true
microjainslee.cluster.node-id=http-1
microjainslee.cluster.stack=tcp
microjainslee.cluster.initial-hosts=http-1[7800],ss7-1[7800]
```

`MicroSleeContainer.start()` must honour `isClusterEnabled()` and, when
`jainslee-cluster` is on the classpath, construct/bind the `ClusterManager`
itself — **or fail loudly** if it is enabled and the module is absent. Today
the flag is a silent no-op, which is the worst possible failure mode.

### D10 — Honest scope: what active/active does **not** buy you

State this in the same ADR, per the LINK STATUS TRUTH law.

- **A parked HTTP request cannot survive the death of its node.** The
  `Vert.x HttpServerResponse` lives in `pendingResponses` on one heap, and the
  TCP connection dies with the socket. R2 for plane 1 means: the **client**
  retries against a healthy node, with the `activityId` as an idempotency key
  so the retry does not produce a second MAP transaction.
- Therefore the HTTP contract must be idempotent and resumable:
  `POST /gmlc {flowId} → 202 {flowId, statusUrl}` and
  `GET /gmlc/{flowId}` or SSE. The OTA app already does this (it returns `202`
  and drives delivery from a `@Scheduled` tick reading `FOR UPDATE SKIP LOCKED`
  from Postgres) — that is the correct shape and should be the documented
  pattern, not an accident.
- USSDGW's long-poll `ClassicNiHttpPark` is the counter-example and **must not**
  be copied into an active/active design without a protocol change.
- Per-node state in consumer apps must be made global or removed: rate limiters
  (N× the intended value), circuit breakers, `OtaServingCache`, DLR correlators,
  MS-digit-claim locks. A rate limiter that silently multiplies by node count
  under active/active is a billing incident waiting to happen.

---

## 3. Phased plan

Each phase is independently shippable and independently revertible.

### P0 — jSS7 prerequisites (blocks R2 for SS7 entirely)

Nothing cluster-side works until a dialog with an **outstanding invoke** can
move. Do this in `coral-valley/jSS7`, bump `ss7.version`, `mvn install`.

| Item | File |
|------|------|
| `PendingInvoke` in the snapshot + `restorePendingInvoke` | `TcapDialogSnapshot.java`, `DialogImpl.java:2296` |
| Idle deadline → epoch ms | `DialogImpl.java:127`, `:2285`, `:2339` — bump `serialVersionUID` |
| Resolver hooks for TC-END / TC-ABORT | `TCAPProviderImpl.java:1081`, `:1120` |
| `Dialog#getPendingInvokeCount()` | `tcap-api/.../Dialog.java` — makes ADR 0001's `hasPendingInvokes()` real |
| Guard the Reject path on imported dialogs | `DialogImpl.processOperationsState` `:1923` |
| **Test with a real pending invoke and a non-zero timeout** | the existing 3 tests all use `setInvokeTimeout(0)`; that is why this is untested |

### P1 — Make the cluster reachable (P0 of *this* repo)

- `MicroSleeContainer.start()` honours `isClusterEnabled()`; fail loud if the
  module is missing.
- `SbbEntityPool` interface; `VirtualThreadSbbEntityPool` un-`final`;
  `bindDistributedSbbPool` actually rebinds the router. **Biggest single
  unlock — `sbb-entity-state` becomes live.**
- `adapter-quarkus` / Spring / JakartaEE cluster config keys (D9).
- Entity id derived from activity id, or cluster-wide allocator.
- Legacy `ResourceAdaptorContext` + `clusterEnabled` → startup warning.
- Delete `flock /tmp/ussdgw.lock` from `build/systemd/ussdgw.service` — a
  single-instance lock makes two nodes on one host impossible by construction.

### P2 — Correlation + inbound routing (R1)

- `MapEventPayload` POJO (`com.microjainslee.*` → allow-list clean).
- `RaStickyEventBus` on `ra-<name>-event`, `DIST_SYNC`.
- `jainslee.ra.sticky.forward.events=true` default; command-side default stays `false`.
- `RaHaSupportTest` — new test pinning the asymmetry.
- `AcnfBackend`: a remote `NamedActivityContext` must produce an actionable
  failure ("no local binding, forward to owner X"), not `IllegalStateException`.

### P3 — Failover + rejoin (R2, R3)

- `RaDialogOwner` gains `leaseExpiresAtEpochMs` + `ownerBootEpochMs`.
- Lease heartbeat (idle dialogs renew), `DialogLeaseReaper` on `@ViewChanged`
  + periodic sweep, CAS with `generation + 1`.
- **Generation enforced on the transmit path.**
- `lookupOwner` stops caching remote ownership locally.
- Off-heap slot bytes in `SbbEntitySnapshot` (D5) + fail-fast guard.
- Timers: node id from `ClusterManager`, entity id instead of identity hash,
  replicated **deadlines** with lease-guarded firing (D6).
- Rejoin: `awaitInitialTransfer(false)` is already set on every cache — verify
  a recovered node rehydrates live dialogs before it accepts traffic, and
  stays **read-only** until `stateTransfer` completes.

### P4 — Performance (high perf, measurably)

Benchmark harness first, then in order: D7 p-a → p-b → p-c → p-d → p-e →
p-f → p-g → p-h.

### P5 — Observability (D8)

Flow id in the event contract · node labels · real instruments · HdrHistogram
p99 · ring lag · latch-await timer · `RaHaMetrics` as gauges · GC counters ·
timer-wheel metrics · pinned-VT watchdog.

### P6 — Lab proof

No "active/active" claim before this. `docs/lab/ss7-multi-asp-failover.md`
exists as a stub.

1. Two nodes, **one AS, N ASPs, same OPC/RC** — if the STP loadshares across
   different OPC/STP pairs, CONTINUEs never reach the survivor and no amount of
   cluster work helps. This is a network prerequisite, not a code problem.
2. Disjoint OTID ranges per node (`Ss7RaConfig.dialogIdRangeStart/End`).
3. Scenarios: kill mid-BEGIN · kill mid-flight with pending invoke · kill after
   CONTINUE · kill during HTTP park · recover and rejoin · partition (not kill)
   — **partition is the real test of the generation fence.**
4. Prove the artifact: jar mtimes, live logs, ISPN cache contents, JFR.

---

## 4. Consequences

**Gains.** A response reaches its HTTP connection regardless of which node
holds the protocol dialog. A dead node's dialogs are reclaimed by lease rather
than orphaned forever. The router stops being the throughput ceiling.

**Costs.** `@OffHeap` becomes a documented opt-out of local state. A generation
fence on the SS7 transmit path adds one ISPN read before each MAP write —
non-trivial at high TPS; cache it and make it a conditional CAS only when a
takeover is in flight. Two nodes mean every per-node knob is now N× — rate
limiters, breakers, pools, timers all need re-review.

**Explicitly rejected.**

| Option | Why rejected |
|--------|--------------|
| Leader/active-passive | The ask is true active/active. A leader for *anything* defeats it. |
| Replicate `InvokeImpl` objects into Infinispan | jSS7 types are not in the allow-list, are not stable `Serializable`, and change with the stack version. Rehydrate from a POJO instead. |
| Add `org.restcomm.*` to the marshalling allow-list | Breaks the deliberate blast-radius fence. |
| Share one `SccpProvider` across nodes | `registerSccpListener` collides on SSN; each node gets its own stack. |
| Move Vert.x into core | Violates the zero-framework-dep law. |
| Forward parked HTTP responses between nodes | Physically impossible — the socket is on one heap. Change the client contract instead (D10). |

**Non-goals for this ADR.** SIP/Diameter/gRPC protocol state machines (they
follow the same fabric via D1–D3, but their per-protocol failover is separate
work). CAMEL. OpenAPI. Prometheus.
