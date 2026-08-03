# ADR 0001 — SS7 RA n-n topology and TCAP dialog failover

- **Status:** Accepted (P1 shipped; P2 jSS7 export/import spike proven in unit test — not production HA)
- **Date:** 2026-08-03 (P2 spike update: 2026-08-03)
- **Deciders:** micro-jainslee maintainers
- **Related code:** `jainslee-cluster`, `vendor-ras/ra-jss7`, jSS7 j25 `TCAPProviderImpl` / `DialogImpl`

## Context

Deployments want **n-n** signalling: RA node-1 ↔ STP1, …, RA-n ↔ STP-n, with SBBs that may run on any cluster node. Messages and SBB replies may cross JVMs. After RA-1 dies, operators want RA-2 to send **TCAP CONTINUE** for dialogs that began on RA-1.

| Capability | Reality |
|------------|---------|
| RA-n ↔ STP-n (per-node SCTP/M3UA) | **Yes** (config / `Ss7Config`) |
| Cross-node SBB fan-in + sticky outbound to owner RA | **Yes (P1)** |
| TCAP CONTINUE after owning RA death | **Spike only (P2)** — unit-test export/import; lab/multi-ASP still open |

jSS7 keeps `DialogImpl` in a JVM-local `NonBlockingHashMap` (`TCAPProviderImpl.dialogs`). CONTINUE for an unknown DTID yields `PAbortCauseType.UnrecognizedTxID`. **P2 spike** adds `TcapDialogSnapshot` + `TCAPProvider.exportDialog` / `importDialog` on jSS7 j25 (coral-valley); micro-jainslee still needs a published/`ss7.version` bump before RA wiring.

## Decision

1. **Sticky outbound (P1):** Continue/End/Abort/MAP for a dialog must go through the RA that owns the live jSS7 dialog (`StickyRaCommandRouter` + `ra-dialog-owner`). Missing owner or `isM3uaRouteReady()==false` → reject (honest).
2. **Write-through (P1):** `Ss7DialogOwnershipTracker` mirrors meta/owner into ISPN when `ClusterManager` is bound; local-only when not.
3. **Remote forward (P1):** `IspnStickyCommandBus` on cache `ra-jss7-sticky-cmd` — same `ClusterManager`, not a second fabric.
4. **CONTINUE takeover (P2):** jSS7 export/import spike exists; full takeover still needs multi-ASP / same-identity network path, invoke/MAP state, and RA wiring.

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
| `ra-dialog-owner` | SLEE `dialogId` | `RaDialogOwner` | same | Sticky fence + generation |
| `ra-jss7-sticky-cmd` | envelope id | `Ss7StickyCommandEnvelope` | `DIST_SYNC` / `LOCAL` | P1 remote outbound |
| `sbb-entity-state` / `slee-acnf` / `slee.queue.*` | — | — | existing | Unchanged |

**Allow-list:** `com.microjainslee.*` only — never `org.restcomm.*` stack objects.

## P2 spike status (2026-08-03) — honesty first

**Not production failover.** Unit-test proof only on jSS7 j25.

### What works in unit test

| Item | Evidence |
|------|----------|
| `TcapDialogSnapshot` POJO | `tcap-api` — local/remote OTID, SCCP addresses, state, ACN OID, idle deadline, networkId, SSN, PC, seqControl, invoke-id bitmap |
| `TCAPProvider.exportDialog(long)` | Returns snapshot; does not remove from `dialogs` |
| `TCAPProvider.importDialog(snapshot)` | Registers rehydrated `DialogImpl` into `dialogs` |
| CONTINUE after rehydrate | `TcapDialogExportImportTest` — same provider and second provider; `processContinue` delivers `onTCContinue`, no P-Abort |

Tree: `worktrees/jSS7/coral-valley/jSS7` (branch `j25`).

### What still blocks real multi-node STP failover

- **Multi-ASP / same AS identity** — Continuations must arrive at the survivor; per-RA OPC/STP pairs do not move traffic.
- **OTID range partitioning** — import preserves local OTID; ranges must not collide across live RAs.
- **Timers** — idle timer re-armed from deadline; invoke operation timers / live `InvokeImpl` objects are **not** restored.
- **Invoke tables** — occupancy bitmap restored; outstanding operation objects / linked invokes are empty after import.
- **MAP/CAP/INAP dialogue state** — above TCAP; not in snapshot.
- **RA wiring** — `ra-jss7` still on sticky P1; needs jSS7 artifact with export/import (`ss7.version` rebuild/publish) before calling the API. Stub: `TcapDialogFailoverPort`.
- **ISPN meta alone** — still insufficient without import into survivor `dialogs`.

```text
TcapDialogSnapshot { long localOtid; byte[] remoteOtid; ... }
TCAPProvider.exportDialog(long localOtid)
TCAPProvider.importDialog(TcapDialogSnapshot)  // registers into dialogs map
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
| **P2** | jSS7 export/import; rehydrate; multi-ASP lab | **Spike done (unit)** / lab+RA wiring open |

### ACNF note

`NamedActivityContext` still returns a name-only handle cross-node. Materialize/live ACI policy remains a follow-up if SBB attach across nodes needs richer ACIs — not required for sticky outbound.

## References

- `jainslee-cluster`: `Ss7DialogClusterCaches`, `TcapDialogMeta`, `RaDialogOwner`
- `vendor-ras/ra-jss7`: `Ss7ResourceAdaptor`, `cluster/*`, `README.md`
- AGENTS.md — LINK STATUS TRUTH
- jSS7 j25: `TcapDialogSnapshot`, `TCAPProvider.exportDialog/importDialog`, `TcapDialogExportImportTest`
- `vendor-ras/ra-jss7/.../TcapDialogFailoverPort` (stub; version bump required)
