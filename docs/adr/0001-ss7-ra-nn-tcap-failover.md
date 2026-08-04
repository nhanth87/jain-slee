# ADR 0001 — SS7 RA n-n topology and TCAP dialog failover

- **Status:** Accepted (P1 shipped; P2 RA-wired — not production / STP-lab HA)
- **Date:** 2026-08-03 (P2 RA wire update: 2026-08-03)
- **Deciders:** micro-jainslee maintainers
- **Related code:** `jainslee-cluster`, `vendor-ras/ra-jss7`, jSS7 j25 `TCAPProviderImpl` / `DialogImpl`

## Context

Deployments want **n-n** signalling: RA node-1 ↔ STP1, …, RA-n ↔ STP-n, with SBBs that may run on any cluster node. Messages and SBB replies may cross JVMs. After RA-1 dies, operators want RA-2 to send **TCAP CONTINUE** for dialogs that began on RA-1.

| Capability | Reality |
|------------|---------|
| RA-n ↔ STP-n (per-node SCTP/M3UA) | **Yes** (config / `Ss7Config`) |
| Cross-node SBB fan-in + sticky outbound to owner RA | **Yes (P1)** |
| TCAP CONTINUE after owning RA death | **RA-wired P2** — export/import + ISPN snapshot + CONTINUE-miss resolver; multi-ASP lab still open |

jSS7 keeps `DialogImpl` in a JVM-local `NonBlockingHashMap` (`TCAPProviderImpl.dialogs`). CONTINUE for an unknown DTID yields `PAbortCauseType.UnrecognizedTxID` unless a `TcapMissingDialogResolver` supplies a snapshot for `importDialog`.

**jSS7 artifact:** `ss7.version` = `9.2.8-j25` (local `mvn install` from coral-valley `j25` required for export/import + missing-dialog resolver).

## Decision

1. **Sticky outbound (P1):** Continue/End/Abort/MAP for a dialog must go through the RA that owns the live jSS7 dialog (`StickyRaCommandRouter` + `ra-dialog-owner`). Missing owner or `isM3uaRouteReady()==false` → reject (honest).
2. **Write-through (P1):** `Ss7DialogOwnershipTracker` mirrors meta/owner into ISPN when `ClusterManager` is bound; local-only when not.
3. **Remote forward (P1):** `IspnStickyCommandBus` on cache `ra-jss7-sticky-cmd` — same `ClusterManager`, not a second fabric.
4. **CONTINUE takeover (P2 wired):** Owner RA exports jSS7 snapshots into ISPN-safe `TcapDialogSnapshotPayload`; survivor imports via `Jss7TcapDialogFailoverPort` / CONTINUE-miss resolver. Not full STP HA.

## P1 shipped

| Item | Location |
|------|----------|
| Ownership tracker + write-through | `ra-jss7/.../Ss7DialogOwnershipTracker` |
| Sticky router | `StickyRaCommandRouter` |
| ISPN sticky command bus | `IspnStickyCommandBus` + `Ss7DialogCacheNames.RA_STICKY_COMMANDS` |
| OTID ranges on flat config | `Ss7RaConfig.dialogIdRangeStart/End` → `Ss7Stack` / `Ss7Config.Tcap` |
| Wire-in | `Ss7ResourceAdaptor.setClusterManager` before `raActive()` |

## Cache design (extend ClusterManager)

| Cache name | Key | Value | Mode | Notes |
|------------|-----|-------|------|-------|
| `tcap-dialog-meta` | dialog key | `TcapDialogMeta` | `REPL_SYNC` / `LOCAL` | Write-through on Begin/Continue; remove on End |
| `tcap-dialog-by-remote` | `remotePc:remoteOtid` | local dialog key | same | Peer-side lookup |
| `tcap-dialog-snapshot` | dialog key | `TcapDialogSnapshotPayload` | same | P2 portable fields (no `org.restcomm.*`) |
| `ra-dialog-owner` | SLEE `dialogId` | `RaDialogOwner` | same | Sticky fence + generation |
| `ra-jss7-sticky-cmd` | envelope id | `Ss7StickyCommandEnvelope` | `DIST_SYNC` / `LOCAL` | P1 remote outbound |
| `sbb-entity-state` / `slee-acnf` / `slee.queue.*` | — | — | existing | Unchanged |

**Allow-list:** `com.microjainslee.*` only — never `org.restcomm.*` stack objects. Snapshots map SCCP addresses to `PortableSccpAddress`.

## P2 RA-wired status (2026-08-03) — honesty first

**Not production failover. Not STP multi-ASP lab HA.**

### What is wired

| Item | Evidence |
|------|----------|
| jSS7 `TcapDialogSnapshot` + `exportDialog` / `importDialog` | coral-valley `j25`; unit tests 3/3 |
| jSS7 `TcapMissingDialogResolver` on CONTINUE miss | `TCAPProviderImpl.tryImportMissingDialog` |
| ISPN `TcapDialogSnapshotPayload` + `tcap-dialog-snapshot` | `jainslee-cluster` |
| RA adapter | `Jss7TcapDialogFailoverPort` — export write-through, `tryTakeover`, CONTINUE-miss resolve |
| RA lifecycle | `Ss7ResourceAdaptor` registers resolver on `raActive()`; exports on Begin/Continue |
| Unit tests (no STP) | `Jss7TcapDialogFailoverPortTest`, `Ss7DialogClusterCachesTest`, sticky P1 tests |

### What still blocks real multi-node STP failover

- **Multi-ASP / same AS identity** — Continuations must arrive at the survivor; per-RA OPC/STP pairs do not move traffic.
- **OTID range partitioning** — import preserves local OTID; ranges must not collide across live RAs.
- **Timers** — idle timer re-armed from deadline; invoke operation timers / live `InvokeImpl` objects are **not** restored.
- **Invoke tables** — occupancy bitmap restored; outstanding operation objects / linked invokes are empty after import.
- **MAP/CAP/INAP dialogue state** — above TCAP; not in snapshot.
- **GT / complex SCCP address fidelity** — portable address stores RI + PC + SSN + GT digits only.
- **Lab proof** — no multi-ASP STP soak; do not claim production HA.

```text
TcapDialogSnapshotPayload { dialogKey, localOtid, PortableSccpAddress, trState, ... }
TCAPProvider.exportDialog(long) → map → ISPN tcap-dialog-snapshot
CONTINUE miss → MissingDialogResolver → importDialog(snapshot)
explicit: TcapDialogFailoverPort.tryTakeover(localOtid) + ownership CAS
```

## Network prerequisite (P2)

Multi-ASP loadshare under the **same** AS / OPC / SSN / RC (or equivalent). Different OPC per RA-i↔STP-i does not move Continuations to a survivor.

## OTID range partitioning

`Ss7RaConfig.dialogIdRangeStart/End` (default `0,0` = jSS7 defaults). Multi-RA: non-overlapping ranges; cluster mode without partition logs a warning at `raActive()`.

## LINK STATUS TRUTH

- Ready: `isM3uaRouteReady()` only.
- Not ready signals: `isActive()`, `isStarted()`, LISTEN.

## Phased roadmap

| Phase | Scope | Status |
|-------|--------|--------|
| **P0** | Honesty docs; cache POJO skeleton | **Done** |
| **P1** | Sticky owner + meta write-through + sticky bus + OTID config | **Done** |
| **P2** | jSS7 export/import; RA wire; ISPN snapshot; CONTINUE-miss; metrics + lab script | **RA-wired** / multi-ASP lab open ([lab doc](../lab/ss7-multi-asp-failover.md)) |

### ACNF note

`NamedActivityContext` still returns a name-only handle cross-node. Materialize/live ACI policy remains a follow-up if SBB attach across nodes needs richer ACIs — not required for sticky outbound.

## References

- `jainslee-cluster`: `Ss7DialogClusterCaches`, `TcapDialogMeta`, `TcapDialogSnapshotPayload`, `RaDialogOwner`
- `vendor-ras/ra-jss7`: `Ss7ResourceAdaptor`, `Jss7TcapDialogFailoverPort`, `cluster/*`, `README.md`
- AGENTS.md — LINK STATUS TRUTH
- jSS7 j25: `TcapDialogSnapshot`, `TcapMissingDialogResolver`, `TCAPProvider.exportDialog/importDialog`, `TcapDialogExportImportTest`
