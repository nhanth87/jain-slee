# ADR 0002 — MS Infinispan transport only via RA / child SBB

- **Status:** Accepted
- **Date:** 2026-08-03
- **Deciders:** micro-jainslee maintainers
- **Related code:** `ms-ispn` (`IspnQueueRaEndpoint`), `adapter-quarkus` (`IspnMsClientSbb`, `MsHttpGatewaySbb`)

## Context

Two docs disagreed on the outbound boundary for cross-node MS calls:

| Source | Rule |
|--------|------|
| Classic JAINSLEE ([AGENTS.md](../../AGENTS.md), [sbb-guide](../en/sbb-guide.md)) | SBB outbound **only** via `RaCommandPort.sendCommand`; transport lives **only** inside RAs |
| MS design ([microjainslee-microservice.md](../vi/microjainslee-microservice.md) INV-3) | `SleeServiceClient` is the MS boundary; `IspnQueueClient` is a client impl |

The Quarkus MS example followed INV-3: `MsHttpGatewaySbb` / `MsAppBridgeSbb` called `MicrosleeBootstrap.client()` (→ `IspnQueueClient` when remote). That bypasses the classic RA port for SBB-visible code.

Audit note: `HttpSbbService` / `HttpRaService` / `HttpAuxService` are **callees** (`SleeServiceHandler`) and do **not** call Infinispan. The violators were gateway/bridge SBBs.

## Decision

1. **Classic RA wins for SBB-visible code.** SBBs and examples must not call `IspnQueueClient`, `IspnTransportManager`, or `MicrosleeBootstrap.client()` for outbound MS.
2. **Allowed outbound:** `@InjectRa(name="ispn-queue-ra")` and/or child [`IspnMsClientSbb`](../../jainslee-adapter/adapter-quarkus/runtime/src/main/java/com/microjainslee/quarkus/ms/IspnMsClientSbb.java) → `RaCommandPort.sendCommand(IspnQueueCommand)`.
3. **`SleeServiceClient` / `IspnQueueClient` remain RA-internal** (and unit-test surface). The RA delegates to `MicrosleeBootstrap.client()` so Direct (local) vs Infinispan (remote) stays transparent.
4. **Bootstrap / `MicrosleeMsSupport`** may construct `IspnTransportManager` and `registerRa(IspnQueueRaEndpoint)`.
5. **Inbound phase 2 (deferred):** `IspnQueueServer` → `RaBootstrapPort.fireEvent(...)` instead of invoking handlers outside SLEE. Phase 1 keeps server→handler for compatibility.

## Target outbound path

```
Gateway / App bridge SBB
  → IspnMsClientSbb (child collaborator)
    → ispn-queue-ra.sendCommand(CallService | NotifyService | QueryServiceState)
      → MicrosleeBootstrap.client() / IspnQueueClient
        → peer SleeServiceHandler
```

## Consequences

- Adapter gateway and example bridge stop importing bootstrap client / transport for MS calls.
- Fail-hard semantics (503 unavailable / 504 timeout) stay inside `IspnQueueClient`; RA surfaces them via command completion.
- README / demo diagrams must show the RA hop, not “gateway → ISPN” as a direct edge.
