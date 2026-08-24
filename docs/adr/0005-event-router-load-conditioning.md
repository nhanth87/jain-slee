# ADR 0005 — Event router queue strategy + SEDA load conditioning (opt-in)

- **Status:** Proposed
- **Date:** 2026-08-24
- **Related:** [improvement-proposal.md §3](../improvement-proposal.md) ·
  research refs [R1][R2][R4][R5] therein

## Context

`EventRouter` is an LMAX Disruptor ring buffer with `BlockingWaitStrategy`
back-pressure. Under overload the producer blocks; there is no per-event-type
admission control and no load-shedding policy, so a slow consumer surface
(e.g. trace/metrics) can back-pressure business traffic. Field demand:
USSDGW 10k-TPS hosts, DRA routing plane, STP transit — all need graceful
degradation instead of head-of-line blocking.

Research anchors:

- **SEDA** (SOSP'01) + adaptive admission control (USITS'03): stages with
  bounded queues + dynamic controllers keep p90 latency bounded under overload.
- **BBQ** (USENIX ATC'22): block-based bounded queue reports up to 11.1×
  Disruptor throughput in macro-benchmarks and natively supports lossy
  drop-old semantics suited to tracing.

## Decision

1. **Queue strategy becomes pluggable behind an interface**
   (`EventQueueStrategy`). The **default remains the existing Disruptor
   path, byte-for-byte behavior** — no default change in this ADR.
2. **BBQ-style backend ships opt-in** (`microjainslee.event-router.queue=bbq`)
   after a JMH harness proves parity on our workloads.
3. **Lossy drop-oldest is legal only for telemetry/trace/monitor queues.**
   Business event paths are never configured lossy; the factory refuses it
   (fail-fast) rather than dropping revenue events.
4. **SEDA-inspired load-conditioning module, flag-gated off by default**
   (`microjainslee.load-conditioning.enabled=false`):
   - per-event-type queue-depth admission control,
   - shedding priority: trace → metrics → management → **business (never)**,
   - controller inputs reuse existing EWMA machinery (pattern proven by
     USSDGW AdaptiveTimeout) and feed AutoReconfigEngine conditions.

## Consequences

- Zero regression risk while flags are off; behavior identical to today.
- Enabling requires per-host benchmark artifacts (prove-the-artifact law).
- New module lives in `jainslee-core` (pure Java, no framework deps);
  Disruptor dependency stays untouched for the default path.

## Alternatives considered

- Replace Disruptor outright — rejected: unbounded blast radius across every
  product tree; contradicts the backbone-safety rule.
- Shedding inside RA code per app — rejected: N copies of the same policy
  (already happens with app-level breakers); runtime must own it once.
