# Active/Active lab — two micro-jainslee nodes (ADR 0007 P6)

- **Status:** script, updated for ADR 0007 D11/D12 (symmetric nodes + witness).
  P0–P3 pass in-process (see the ADR); **no active/active claim is valid until
  this lab passes.**
- **P6 in-process leg: PASS.** `ActiveActiveTwoNodeLabTest` runs two **real**
  Infinispan/JGroups members over TCP loopback (not one manager faking two
  views) and asserts:

  | Scenario | What it proves | Status |
  |----------|----------------|--------|
  | **A** | A response produced on the SS7 node reaches the **HTTP node** that owns the socket (R1) | ✅ |
  | **B** | The owner consumes the envelope **exactly once** — no double delivery | ✅ |
  | **C** | A **partitioned** owner is fenced out of transmitting while a survivor takes over | ✅ |
  | **D** | A **killed** node's lease is reclaimed instead of orphaned forever | ✅ |
  | **G** | A restarted node re-takes its own lease with a bumped generation; the old incarnation is fenced by boot epoch | ✅ |

  ```bash
  mvn -pl jainslee-cluster test -Dtest=ActiveActiveTwoNodeLabTest
  ```

  **C is the load-bearing one.** A kill is easy — a dead node stops. C keeps the
  zombie alive, still holding its socket, still believing it owns the dialog, and
  asserts its `mayTransmit()` now refuses and is counted in `fenceBlocked()`.
  That is what stops a second MAP transaction reaching the SMSC.

  **Still lab-only** (needs a real STP and a real client): the SS7 wire half —
  multi-ASP loadsharing, OTID range partitioning, CONTINUE takeover through
  `TcapMissingDialogResolver`. §1 onward is that runbook.
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

### Running two nodes under systemd (USSDGW)

`build/systemd/ussdgw.service` (node1) and the new `build/systemd/ussdgw@.service`
template (any node id) are tracked. Two operator notes that are easy to get wrong:

1. **`build/systemd/install-on-digicom.sh` is intentionally gitignored** — it
   holds Digicom-specific install commands and is never tracked on public main.
   The template therefore has to be installed by hand on a fresh checkout:
   ```bash
   sudo install -m 0644 build/systemd/ussdgw@.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now ussdgw@node2
   ```
2. **The `flock` was NOT removed.** It is a restart-race guard; deleting it
   re-creates the proven Digicom crash-loop. Only its path was namespaced per
   node (`/tmp/ussdgw-%i.lock`), so N nodes coexist and each still refuses to
   double-start. See `docs/agents/lessons.md` in the USSDGW tree.
3. Each node needs its **own** `WorkingDirectory` (`ussdgw-<id>/`) — `run.sh`
   hardcodes `$APP_HOME/configs`, `data/` and `logs/`, so sharing one tree would
   collide the profile store and the config.

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
# JMH module (pending-issues P4): real numbers, recorded 2026-11-20.
mvn -pl jainslee-bench -am package -DskipTests
java -jar jainslee-bench/target/benchmarks.jar
```

| Path | Baseline (measured 2026-11-20, JDK Zulu 25.38) | Target |
|------|-----------------------------------------------|--------|
| 1→1, no observer | **4,643,504 ops/s** (± 3,304,788, 3 iters) | ≥ 500k ev/s ✅ (~9×) |
| 1→N (N=100), no observer | **1,157,409 ops/s** (± 636,576) | sort/copy cost quantified |
| MDC on vs off | ? | quantify the P4-d win |
| N attached SBBs | 1→100 costs ~4× (sort/copy per event) | cache the sorted attachment list |

Decision: `event-router.shards` stays at default 1 and `ASYNC_COMMIT` stays
opt-in — the single worker is not the ceiling at these numbers. Full table +
rerun policy: `jainslee-bench/README.md`.

---

## Result log

| Date | Operator | Commit | Scenarios | Outcome |
|------|----------|--------|-----------|---------|
| 2026-11-20 | nhanth87 (agent) | feat/aa-cluster (1.2.3-SNAPSHOT round) | A/B (claim + cross-node fence), D (kill -9 owner → takeover gen+1), G (restart rejoin, old incarnation fenced), C (STOP-partition, majority takes over, zombie fenced after heal), C2 (2 nodes WITHOUT witness stay fenced) — **3-JVM loopback lab** (ss7-1/ss7-2/witness, JGroups TCP 127.0.0.1, lease/fence layer) | **25/25 PASS, real measured output.** In-process: `ActiveActiveTwoNodeLabTest` A/B/C/D/G + `RaDialogLeaseFenceTest` (incl. twoNodesWithoutWitnessCannotTakeOver) green; core 523, cluster 115, ra-jss7 127, all green. Honest scope: fabric-level (leases/fence/takeover/witness) across 3 JVMs + in-process SS7-plane tests (D11 router, CONTINUE-miss resolver, snapshot fidelity). **NOT run:** SS7 wire half (STP/HLR simulators over kernel SCTP) — no claim on multi-ASP loadshare or TCAP CONTINUE over the wire. JMH baseline (JDK Zulu 25.38): 1→1 4.64M ops/s, 1→N(100) 1.16M ops/s — shards stay 1, ASYNC_COMMIT stays opt-in. |
| 2026-11-20 | nhanth87 (agent, independent 2nd harness) | feat/aa-cluster (same tree) | Same 3-JVM loopback shape, separate ports/control harness (`/tmp/aa-lab`, TTL 15 s): A (claim fenced, mayTx owner-only), D (kill -9 → `takeOver=true`, mayTx flips), G (rejoin: claim/takeover refused), C (STOP owner → majority `takeOver=true`, post-heal zombie `mayTransmit=false`), C2 (witness TERM + peer STOP, **verified `members=1`** → `takeOver=false`, `mayTransmit=false`), H (rolling restart → view 3) | **18/20 + focused C2 5/5 PASS.** First attempt voided by a harness quoting bug (control channel sent unquoted python → all commands errored; two view-checks false-passed on the TIMEOUT text) — fixed (`argv`-based control, TIMEOUT marker without the pattern) and rerun from scratch. Corroborates the row above with an independent harness, incl. the C2 minority precondition the first attempt skipped. Same honest scope: fabric layer only, no STP/HLR wire half. |
| — | — | — | — | not yet run (row above supersedes) |
