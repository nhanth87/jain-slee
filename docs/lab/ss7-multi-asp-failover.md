# Lab — multi-ASP TCAP CONTINUE failover soak (ADR 0001 P2)

> **Status:** Lab procedure — **not** production / STP multi-ASP HA claim.  
> **Metrics:** `Ss7ResourceAdaptor.failoverMetrics().snapshot()`  
> **Related:** [`docs/adr/0001-ss7-ra-nn-tcap-failover.md`](../adr/0001-ss7-ra-nn-tcap-failover.md)

## Prerequisite (network)

Both RA nodes must share the **same** AS / OPC / SSN / routing context (multi-ASP loadshare).
Per-RA distinct OPC↔STP pairs do **not** move Continuations to a survivor.

OTID ranges on each RA must be **non-overlapping** (`Ss7RaConfig.dialogIdRangeStart/End`).

## Procedure

1. Start RA-A and RA-B with cluster ISPN + sticky bus + failover port wired.
2. Open a MAP/TCAP dialog on RA-A; confirm `ss7_tcap_failover_export_ok_total` increments on Begin/Continue.
3. Kill RA-A mid-dialog (or drop its ASP) while the peer still has the AS ACTIVE via RA-B.
4. Peer sends TCAP CONTINUE toward the shared OPC/SSN.
5. Expect RA-B CONTINUE-miss → `MissingDialogResolver` → import → dialog resumes.
6. Confirm counters:
   - `ss7_tcap_failover_continue_miss_total` ≥ 1
   - `ss7_tcap_failover_import_ok_total` ≥ 1 (or takeover_ok)
   - `ss7_tcap_failover_import_fail_total` == 0 for a green soak
7. `ss7.live` / `isM3uaRouteReady()` must stay peer-truth (never LISTEN/`isActive()` alone).

## Script

```bash
# From jain-slee repo root (after both RAs are up):
./scripts/lab-ss7-tcap-failover-soak.sh --metrics-url http://127.0.0.1:8088/admin/ss7/failover-metrics
```

The script polls a metrics JSON endpoint if the host exposes one; otherwise print the
expected counter names for manual scrape via `failoverMetrics().snapshot()`.

## Pass / fail

| Gate | Pass |
|------|------|
| Mid-dialog ASP kill | CONTINUE resumes on survivor |
| Import failures | 0 during soak window |
| Sticky miss under healthy owner | 0 (miss only when owner truly gone) |
| Link status | DOWN when peer gone without UI hacks |

Do **not** tag production HA until this lab is green on a real multi-ASP topology.
