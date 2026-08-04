# ADR 0002 — MS Infinispan transport only via RA / child SBB

- **Status:** Accepted (full MS command surface + inbound dual-mode)
- **Date:** 2026-08-03 (full RA update: 2026-08-03)
- **Deciders:** micro-jainslee maintainers
- **Related code:** `ms-ispn` (`IspnQueueRaEndpoint` / `IspnQueueResourceAdaptor`), `adapter-quarkus` (`IspnMsClientSbb`, `MsHttpGatewaySbb`)

## Context

Two docs disagreed on the outbound boundary for cross-node MS calls:

| Source | Rule |
|--------|------|
| Classic JAINSLEE ([AGENTS.md](../../AGENTS.md), [sbb-guide](../en/sbb-guide.md)) | SBB outbound **only** via `RaCommandPort.sendCommand`; transport lives **only** inside RAs |
| MS design ([microjainslee-microservice.md](../vi/microjainslee-microservice.md) INV-3) | `SleeServiceClient` is the MS boundary; `IspnQueueClient` is a client impl |

## Decision

1. **Classic RA wins for SBB-visible code.** SBBs/examples must not call `IspnQueueClient`, `IspnTransportManager`, or `MicrosleeBootstrap.client()` for outbound MS.
2. **Allowed:** `@InjectRa(name="ispn-queue-ra")` and/or child `IspnMsClientSbb` → `IspnQueueCommand`.
3. **`SleeServiceClient` / `IspnQueueClient` remain RA-internal.**
4. **Keep RA in `ms-ispn`** — Infinispan is the internal JAINSLEE MS fabric, not a vendor-ras module.
5. **Full MS command surface:** Call, Notify, QueryServiceState, PublishServiceState, EnsureServiceCaches, QueryNodeId, QueryServiceStateRecord, ReplyRemoteRequest.
6. **Inbound dual-mode:**
   - **HANDLER (default):** `IspnQueueServer` → `SleeServiceHandler` (demos / `@SleeService` handlers).
   - **EVENT (opt-in):** `fireEvent(MsRemoteRequestEvent)`; SBB completes `response()` or uses `ReplyRemoteRequest`.

## Target outbound path

```
Gateway / App bridge SBB
  → IspnMsClientSbb
    → ispn-queue-ra.sendCommand(IspnQueueCommand.*)
      → MicrosleeBootstrap.client() / IspnQueueClient / IspnTransportManager
```

## Consequences

- Fail-hard semantics stay inside `IspnQueueClient`.
- Example keeps HANDLER inbound — no demo breakage.
- Raw Infinispan Cache CRUD is **not** exposed on this RA.
