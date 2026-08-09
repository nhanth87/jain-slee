# ADR 0002 — Shared RA sticky HA fabric (Gate A)

- **Status:** Accepted (P1 wired)
- **Date:** 2026-08-09
- **Related:** [ADR 0001](0001-ss7-ra-nn-tcap-failover.md) (SS7 TCAP plane)

## Context

SS7 RA has sticky ownership + TCAP snapshot failover. Other protocol RAs
(SIP, HTTP, Diameter, gRPC) need the same **n-n sticky** plane without copying
jSS7-specific caches. SBB create/delete must stay off the HA hot path.

## Decision — Gate A + sync-path default

**HA authority belongs to the RA only.**

| Actor | Role |
|-------|------|
| RA | Ownership, portable session meta, endpoint lease, **`checkpointSbbEntity` at create / state-changing continue**; owns the live connection |
| SBB | Ordinary `@CmpField` + ProfileFacility; **never** call checkpoint. Must send replies on the **same RA/connection** that opened toward the peer/AS (sync path). Find that RA via local activity stickiness / `RaHaSupport.isLocalOwner` / owner lookup — do not invent a second hop |
| ISPN `sbb-entity-state` | CMP + `profileRefs` + generation for peer recreate |

### Sync path (default for HTTP / gRPC / SIP / Diameter)

```text
Peer/AS connection opened on RA-node-A
  → ownership(activityId) = node-A
  → SBB handling the session must run / send on node-A’s RA
  → response goes out on that same connection
```

- Remote owner → **REJECT** by default (`jainslee.ra.sticky.forward=false`).
- ISPN sticky-bus **FORWARD** is opt-in only (`-Djainslee.ra.sticky.forward=true`) — not the default sync path.
- SS7 may keep sticky-bus forward for MAP/TCAP sticky outbound (ADR 0001); HTTP/SIP/Diameter/gRPC follow sync-path default.

## Shared fabric (`jainslee-cluster`)

| Type | Purpose |
|------|---------|
| `RaActivityCacheNames` | `ra-{name}-owner`, `ra-{name}-sticky-cmd`, `ra-{name}-session-meta` |
| `RaActivityOwnerCaches` | Ensure caches on one `ClusterManager` |
| `RaOwnershipTracker` | Local + write-through owner / meta |
| `RaStickyRouter` / `RaStickyCommandBus` | SEND_LOCAL / FORWARD_REMOTE / REJECT |
| `RaSessionMeta` | Portable string attrs only (no stack types) |
| `RaCheckpointBridge` | Reflective RA → `MicroSleeContainer.checkpointSbbEntity` |
| `RaHaSupport` | Bundle for RA wiring; sync-path rewrite of FORWARD→REJECT |
| `RaHaMetrics` | `sticky_reject`, `sticky_forward`, `owner_claim`, `meta_put`, `ra_checkpoint_*` |

SS7 keeps ADR 0001 caches (`Ss7DialogClusterCaches`) and adds Gate A
checkpoint on TCAP Begin + Continue-with-components.

## Per-RA P1

| RA | Activity key | Notes |
|----|--------------|-------|
| `ra-sip-servlet` | Call-ID | Sync path default; portable peer attrs |
| `ra-http-client` | `sessionId` | Sync path; opt-in sticky `HttpStickyPost` |
| `ra-http-server` | ingress `sessionId` | Ownership + checkpoint; reply on same listen RA |
| `ra-diameter` | Session-Id | Sync path + TCP endpoint lease |
| `ra-grpc-client` / `ra-grpc-server` | correlation / call id | Sync path; reply on owning RA connection |
| `ra-jss7` | dialogId | ADR 0001 sticky bus + Gate A checkpoint |

## Honesty

Not production multi-node HA until per-RA lab soak. Metrics + unit tests only.

## Non-goals

- SBB-initiated checkpoint
- Full SIP/Diameter/HTTP/gRPC protocol SM failover
- HA for camel / openapi / prometheus
- Claiming production multi-node HA without lab soak
