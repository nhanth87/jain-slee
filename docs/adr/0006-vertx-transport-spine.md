# ADR 0006 — Vert.x transport spine for IP resource adaptors

- **Status:** Proposed
- **Date:** 2026-08-24
- **Related:** [improvement-proposal.md §3](../improvement-proposal.md) ·
  research refs [R6][R7][R10] therein · `SipTransport` seam (ra-sip-servlet)

## Context

Vert.x/Netty already powers `ra-http-server`, `ra-http-client`,
`ra-openapi` (HTTP/2 + experimental HTTP/3) and the Prometheus exporter.
The long-term plan replaces Netty transports with a DPDK datapath pushing
events into the JVM, which is why all transport must sit behind interfaces.
Today each RA hand-rolls its own event-loop wiring, thread-hop policy, and
connection bookkeeping — and the DEBS'23 Quarkus×Loom study [R6] shows that a
careless Loom↔event-loop "dance" allocates abundant intermediate structures,
measurably increasing GC pressure. io_uring measurements [R10] show wins only
when IO-heavy with many connections per loop, and regressions when event-loop
thread counts are kept at epoll levels.

## Decision

1. **Vert.x is the standard transport substrate for IP RAs**, consumed only
   inside `jainslee-ra-spi` helpers and vendor RAs. `jainslee-api` and
   `jainslee-core` remain pure Java 25 with zero framework imports (unchanged law).
2. **Dispatch policy (the core of this ADR):**
   - RA network callbacks fire SLEE events into the router **directly on the
     Vert.x event loop** — no worker-thread hop, no per-event executor task;
   - outbound `RaCommandPort.sendCommand` executing on an SBB virtual thread
     returns to the originating event-loop context via `runOnContext`
     before touching channel state;
   - shared helper in `jainslee-ra-spi` implements this once; RAs stop
     copy-pasting it.
3. **Shared `VertxTransportOptions`:** event-loop sizing, TLS, domain sockets,
   TCP fast-open, read/write buffer watermarks — one config surface, uniform
   Micrometer metrics into `jainslee-telemetry`.
4. **io_uring is opt-in per RA** (`transport=epoll|io_uring`, default epoll)
   with automatic reduced event-loop count when io_uring is selected; enabling
   on any host requires a recorded benchmark artifact first.
5. **WebSocket / SSE connections become first-class activities:** each
   connection id maps to an `ActivityHandle` with standard attach semantics
   and idle end-activity sweep (replaces chipchipvoice-style singleton
   endpoint keying by hand).
6. **DPDK/F-Stack readiness preserved:** the same spine sits behind
   transport-seam interfaces (`SipTransport` pattern), so the native sidecar
   swaps under Vert.x without touching RA business code.

## Consequences

- One dispatch implementation instead of one per RA; allocation delta must be
  measured with JFR before claiming improvement (R6 methodology).
- Default behavior of existing RAs is preserved; migration to the helper is
  incremental per RA, each behind tests.
- No new dependency enters api/core; vertx deps remain confined to ra-spi/vendor.

## Alternatives considered

- Keep per-RA wiring — rejected: the exact duplication that produced the
  inconsistent park/thread policies observed across consumer trees.
- Move Vert.x into core — rejected: violates the zero-framework-dep law and
  blocks GraalVM-native goals for non-IP deployments.
