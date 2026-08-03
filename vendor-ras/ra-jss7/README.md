# ra-jss7

SS7 Resource Adaptor (SCTP → M3UA → SCCP → TCAP → MAP/CAP) for micro-jainslee.

## Clustering / n-n (ADR 0001)

- **P1 (shipped):** sticky dialog ownership write-through + outbound routing to the owner RA via Infinispan (`Ss7DialogOwnershipTracker`, `StickyRaCommandRouter`, `IspnStickyCommandBus`). Bind a `ClusterManager` with `Ss7ResourceAdaptor.setClusterManager(...)` before `raActive()`.
- **P2 (RA-wired, not STP-lab HA):** `Jss7TcapDialogFailoverPort` calls jSS7 `exportDialog` / `importDialog`, stores `TcapDialogSnapshotPayload` in ISPN, and registers `TcapMissingDialogResolver` for CONTINUE miss. Multi-ASP / MAP state / invoke timers remain open.

Design: [`docs/adr/0001-ss7-ra-nn-tcap-failover.md`](../../docs/adr/0001-ss7-ra-nn-tcap-failover.md)

## Link status truth

Use `Ss7ResourceAdaptor.isM3uaRouteReady()` for peer route readiness — never `isActive()` / `Ss7Stack.isStarted()` alone.

## jSS7 dependency

`ss7.version` = `9.2.8-j25`. Requires a local (or published) install of coral-valley `j25` that includes `exportDialog` / `importDialog` / `TcapMissingDialogResolver`.
