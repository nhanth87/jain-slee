# Active/Active lab — two micro-jainslee nodes (ADR 0007 P6)

- **Status:** script, updated for ADR 0007 D11/D12 (symmetric nodes + witness).
  P0–P3 pass in-process (see the ADR); **no active/active claim is valid until
  this lab passes.**
- **Related:** [ADR 0007](../../adr/0007-true-active-active.md) ·
  [ADR 0001](../../adr/0001-ss7-ra-nn-tcap-failover.md) ·
  [ss7-multi-asp-failover](../ss7-multi-asp-failover.md)

## 0. The law

Per the prove-the-artifact rule in `AGENTS.md`, a green `mvn test` is **not**
evidence. Every claim below needs: a packaged dist, a restarted process, the
running PID's classpath, live logs, and a captured response.

```
NO ACTIVE/ACTIVE CLAIM WITHOUT A RECORDED RUN OF §4 SCENARIO C (partition).
```

## 1. Topology (ADR 0007 D11 + D12 — symmetric, with a witness)

```text
                ┌──────────────── LB (sticky-free) ─────────────────┐
 client ──HTTP▶ │ ss7-1                          ss7-2              │
                │ ra-http-server + ra-jss7       ra-http-server +    │
                │ OTID [1, 1e9]                  ra-jss7             │
                │                                OTID [1e9+1, 2e9]   │
                └──────┬──────────────────────────────┬──────────────┘
                       │  JGroups TCP 7800            │
                       └────────────┬─────────────────┘
                                    │
                               witness-1          (third host; cluster module only,
                                                    no RA, no socket, no dialog)
   ss7-1 ─SCTP─┐                                    ┌─SCTP─ ss7-2
               └────── STP: ONE AS · 2 ASPs · SAME OPC/RC ──────┘
```

Every node is a full node. An SBB always sends through **its own** SS7 stack,
so the node that owns the TCAP dialog is also the node holding the client's
HTTP connection. When the STP load-shares a CONTINUE/END/ABORT onto the other
ASP, that node sees a DTID outside its own range and forwards the **raw PDU**
to the owner (`ss7_tcap_foreign_pdu_forwarded_total`), which processes it with
its own dialog state. Nothing decoded crosses the cluster.

**Prerequisites that are NOT code:**

| # | Requirement | Why |
|---|-------------|-----|
| 1 | One AS, both ASPs, **same OPC/RC** | Otherwise the survivor never receives the peer's CONTINUE |
| 2 | **Disjoint OTID ranges** per node (`dialogIdRangeStart/End`) | The DTID names the owner. Cluster mode **refuses to start** without a range or with an overlapping one |
| 3 | One SCTP association per node, `channel: "sctp"` | never TCP |
| 4 | Separate SCCP stack per node | `registerSccpListener` collides on SSN |
| 5 | **Witness on a third host** | two members cannot form a majority; without it a split fences **both** nodes |
| 6 | Client retries with the same `activityId` | §5 — a parked HTTP request dies with its node |
| 7 | NTP on all hosts | lease expiry is compared against the owner's own clock only, but log correlation needs it |

## 2. Configuration

```properties
# ss7-1
microjainslee.container.cluster-enabled=true
microjainslee.container.cluster-node-id=ss7-1
microjainslee.container.cluster-stack=tcp
microjainslee.container.cluster-initial-hosts=ss7-1[7800],ss7-2[7800],witness-1[7800]
# ra-jss7 (ss7.json "tcap" or Ss7RaConfig)
dialogIdRangeStart=1
dialogIdRangeEnd=1000000000
```

```properties
# ss7-2 — same, with
microjainslee.container.cluster-node-id=ss7-2
dialogIdRangeStart=1000000001
dialogIdRangeEnd=2000000000
```

```bash
# witness-1 — third host
java -Dmicrojainslee.container.cluster-node-id=witness-1 \
     -Dmicrojainslee.container.cluster-initial-hosts=ss7-1[7800],ss7-2[7800],witness-1[7800] \
     -Dmicrojainslee.witness.ra-names=ra-jss7 \
     -cp 'lib/*' com.microjainslee.cluster.ClusterWitness
```

`ra-names` must list the RA names whose leases the witness votes on (the RA's
`raName`, default `ra-jss7`).

## 3. Verify the wiring before any traffic

| Check | Expected log / signal |
|-------|----------------------|
| Cluster up on all three | view of 3 members on every node |
| Node ids are real | `nodeId=ss7-1` / `ss7-2` / `witness-1`, never `local` |
| D11 router on both SS7 nodes | `[ra-jss7] inbound dialog router started node=ss7-1 range=[1, 1000000000]` |
| Witness voting | `Cluster witness up node=witness-1 voting on [ra-jss7]` |
| **Not** this | `cluster mode requires a TCAP OTID range` / `overlaps` — fix the config, do not work around it |

## 4. Scenarios

Each: capture both SS7 nodes' logs, the witness log, the lease / foreign-PDU
counters, and the client-visible outcome.

| ID | Action | Pass criterion |
|----|--------|----------------|
| **A** | Happy path, STP returns the CONTINUE to the owner | result delivered; `foreign_pdu_forwarded_total` = 0 |
| **B** | Rebalance ASPs so the CONTINUE lands on the **other** node | result delivered by the owner; `forwarded_total` > 0 on the receiver, `received_total` > 0 on the owner; no P-Abort |
| **C** | **PARTITION** ss7-2 from {ss7-1, witness} (`iptables` on 7800, **not** kill); send traffic for dialogs owned by ss7-2 | ss7-2 logs `FENCE: not transmitting … partition minority` and sends **nothing** for them; ss7-1 takes over (`took over activity=otid:… (gen 0 -> 1)`); **one** MAP answer per dialog on the wire (pcap). After healing, ss7-2 still refuses to send on the taken dialogs |
| **C2** | Same partition **without** the witness | both nodes fence; **no** takeover anywhere — expected, documents why D12 exists |
| **D** | `kill -9` ss7-2 mid-BEGIN; client retries same `activityId` on ss7-1 | a retry while ss7-2 is still in the view is refused (never two concurrent BEGINs); once ss7-2 has left the view the retry proceeds. After a crash the guarantee is **at-least-once** — the first BEGIN may already have reached the HLR |
| **E** | `kill -9` ss7-2 after the CONTINUE, before the reply | ss7-1 takes the dialog over (resolver + lease CAS); pending invoke resumed, no `Reject(UnrecognizedInvokeID)` towards the peer |
| **F** | `kill -9` the node holding a parked HTTP request | the client's connection dies — expected, see §5 |
| **G** | Restart ss7-2 | leases of its previous incarnation are superseded (gen+1) on OTID reuse; it rejoins without operator action |
| **H** | Rolling restart | `orphansCollected` rises and settles; own-lease count returns to ~in-flight dialogs (`staleReleased` catches missed releases) |

## 5. What scenario F proves about the client contract

A parked HTTP request **cannot** survive its node. The `Vert.x HttpServerResponse`
lives in `pendingResponses` on one heap and the socket dies with the process.

So R2 for the transport plane means **client-side retry with an idempotency
key**:

```text
POST /gmlc  { "activityId": "gmlc-<uuidv7>" }   → 202 { "activityId": "...", "statusUrl": "..." }
GET  /gmlc/{activityId}                          → 200 { state, result }
```

`activityId` is the correlation key end to end (ADR 0007 D1) and doubles as the
idempotency key: a retry must reuse it, otherwise a second MAP
`sendRoutingInfoForSM` is sent to the HLR.

The OTA app already does this (202 + `@Scheduled` tick reading
`FOR UPDATE SKIP LOCKED`), which is the correct shape. `ClassicNiHttpPark`
long-polling in USSDGW is the counter-example and must not be copied here
without a protocol change.

## 6. Metrics that must move

| Metric | Scenario | Expected |
|--------|----------|----------|
| `ss7_tcap_foreign_pdu_forwarded_total` | B | > 0 on the receiving node |
| `ss7_tcap_foreign_pdu_received_total` | B | > 0 on the owner |
| `ss7_tcap_foreign_pdu_owner_absent_total` | D, E | > 0 on the survivor |
| `ss7_tcap_foreign_pdu_send_fail_total` | all | 0 outside kill tests |
| lease `fenceBlocked` | C | > 0 on the minority, 0 elsewhere |
| lease `takeoversOk` / `takeoversRefused` | C, D, E | ok on the majority; refused while the owner is alive |
| `jainslee_delivery_latch_await_seconds_total` | MAP in flight | flat — the router is held for `onEvent`, not for the MAP round trip |
| `jainslee_delivery_timeout_total` | all | 0 |

Every series carries `node="ss7-1"` / `node="ss7-2"`.

## 7. Perf harness (do this first)

Every throughput claim in ADR 0007 D7 is currently a projection. Before
benchmarking the cluster, establish the single-node baseline:

```bash
# JMH harness is still @Ignore'd pending setup — enable it first.
mvn -pl jainslee-core test -Dtest=EventRoutingBenchmark
```

| Path | Baseline (to measure) | Target |
|------|-----------------------|--------|
| 1→1, no observer | ? | ≥ 500k ev/s |
| 1→1, telemetry bound | ? | ≥ 200k ev/s |
| MDC on vs off | ? | quantify the P4-d win |
| N attached SBBs | ? | sort/copy cost |

Report the measured numbers, not the projections.

---

## Result log

| Date | Operator | Commit | Scenarios | Outcome |
|------|----------|--------|-----------|---------|
| — | — | — | — | not yet run |
