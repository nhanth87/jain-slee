# ADR 0004 — Runtime wiring hardening (P0)

- **Status:** Accepted — implemented 2026-08-24 (P0-1..P0-4; full-suite
  regression 1065→1099 tests, same single pre-existing SIP failure, red-check
  verified; ordering-safe refinement added after ra-grpc-server / ra-camel /
  ra-sip-servlet E2E caught the SBB-before-RA registration flow)
- **Date:** 2026-08-24
- **Related:** [improvement-proposal.md §2 P0](../improvement-proposal.md) ·
  [ADR 0003](0003-ra-admin-dashboard-packs.md) (SPI discovery context)

## Context

Four field incidents repeat across consumer trees and all trace to runtime
wiring seams:

1. **Split-package stub wins the classpath.** `jainslee-api` ships a
   `ProfileAccessorInvoker` stub that throws `UnsupportedOperationException`.
   Under Quarkus fast-jar layering the api jar is loaded before `jainslee-core`,
   so the stub beats the real implementation in production. USSDGW lost NI
   traffic (`POST /ussd` → 500) and both USSDGW and GMLC now maintain a
   post-build jar-shadowing step (`shadow-profile-accessor.sh`) to patch the
   class back — a per-app tax paid on every release.
2. **Global profile locator rebind.** Any code path constructing
   `new InMemoryProfileFacility()` silently rebinds the static
   `ProfileFieldStoreLocator`; CMP writes then miss the table that
   `container.getProfileFacility().ensureTable()` created (`ussdTx`
   incident; app-side workaround: re-bind + re-ensure in `VirtualSessionStore.put`).
3. **Silent null RA ports.** `@InjectRa(name)` mismatch with
   `RaEndpointPort.getRaName()` yields a null port and silently dropped
   commands (junior-dev-guide classic mistake #4).
4. **Unfed telemetry seam.** No RA or container path calls
   `RaCollector.updateState(raName,state,port)`; monitor snapshots show
   `state=UNKNOWN, port=0` forever.

## Decision

1. **No throwable stub classes in `jainslee-api`.** API exposes interfaces;
   implementations are discovered via ServiceLoader from core (or injected by
   the container). Missing implementation = boot failure with an explicit
   message naming the missing service. The split-package pair is dissolved.
2. **Profile facility ownership moves to the container instance.**
   `ProfileFieldStoreLocator` becomes per-container, injected at construction,
   immutable thereafter. The global-static rebinding constructor of
   `InMemoryProfileFacility` is deprecated and logs ERROR.
3. **Boot-time wiring validation.** During `MicroSleeContainer.start()`:
   scan every registered SBB type for `@InjectRa` fields and verify each name
   resolves against the RA command-port registry; unresolved names fail the
   boot listing expected vs registered names.
4. **Container feeds RA telemetry automatically.** All RA state transitions
   (`registerRa`, legacy `registerResourceAdaptor` state machine changes,
   port bind) publish into `RaCollector.updateState` through a default
   observer installed unless the app overrides it.

## Consequences

- Apps delete `shadow-profile-accessor.sh` and the `ussdTx` re-bind hack.
- Misconfiguration now fails fast at boot instead of failing traffic at
  runtime (intended behavior change; flagged in CHANGELOG).
- No existing public signature removed; deprecated members delegate.
- Regression tests required: classpath-order simulation test (api-before-core),
  locator-rebind test, @InjectRa mismatch boot-fail test (seen red first).

## Alternatives considered

- Keep shadow scripts per app — rejected: every consumer tree pays forever.
- Classpath ordering hacks / Quarkus producer priorities — rejected: fragile,
  non-portable across adapters (Quarkus/Spring/embedded).
