# PolyVoice N-N lessons → micro-jainslee

**Date:** 2026-08-06  
**Source:** PolyVoice grilling (parallel shard, `correlationId`, shrink-pool, idempotent `(cid,seq)`, zombie TTL)  
**Runtime tree:** `jain-slee` branch `micro-jainslee-2`

## Mapping

| PolyVoice app pattern | micro-jainslee counterpart |
|----------------------|----------------------------|
| Logical session `correlationId` + N workers | SS7 sticky `RaDialogOwner` + ISPN caches (ADR 0001) — not app JDBC |
| Shrink-pool (no reassign) | Sticky REJECT / sticky-miss when owner missing; never nearest-RA forge |
| Idempotent `(cid, seq)` ledger | App-layer (ussdgw/polyvoice PG); RA keeps dialog generation fence |
| Zombie session TTL reaper | `SessionRecoveryServiceImpl` **TTL 5 min** + `reclaimExpired()` |
| Shared TPS admit | App `TenantGuard` / ussdg TenantGuard — not SLEE core |
| Scrapeable failover counters | `TcapFailoverMetrics` on SS7 admin status `failoverMetrics` |

## Code landed this pass

1. **SS7 P2 metrics scrape** — [`Ss7LinkStatusSnapshot`](../../vendor-ras/ra-jss7/src/main/java/com/microjainslee/ra/jss7/admin/Ss7LinkStatusSnapshot.java) embeds `ra.failoverMetrics().snapshot()` (export/import/sticky-miss/reject/takeover/CONTINUE-miss).
2. **SessionRecovery reclaim** — TTL default `300_000` ms; expired snapshots dropped on register/get/rehydrate; counters `reclaimedExpired` / `rejectedStale`.

## Explicitly out (Runtime P2 OffHeap)

APT `@OffHeap` / soak ≥100k remains gap-analysis **Runtime P2** — not driven by PolyVoice N-N.

## References

- [`docs/adr/0001-ss7-ra-nn-tcap-failover.md`](adr/0001-ss7-ra-nn-tcap-failover.md)
- PolyVoice: `worktrees/voice-service/polyvoice/docs/agents/multi-node.md`
