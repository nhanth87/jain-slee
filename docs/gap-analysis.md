# Gap analysis — micro-jainslee Production-1 / Production-2

> **Date:** 2026-08-04  
> **BOM:** `1.2.0-SNAPSHOT` — **P1 shipped**; **targeting 1.2.0-P2** (runtime OffHeap + separate SS7 HA lab claim)  
> **Companion:** [`docs/en/design-offheap-sbb-state.md`](en/design-offheap-sbb-state.md), [`docs/adr/0001-ss7-ra-nn-tcap-failover.md`](adr/0001-ss7-ra-nn-tcap-failover.md)

## Two P2 labels (do not merge)

| Label | Meaning | Tag / claim |
|-------|---------|-------------|
| **Runtime P2** | Opt-in `@OffHeap` CMP (Direct ByteBuffer / Agrona arena), codegen + soak ≥100k | Enough to brand **`1.2.0-P2`** |
| **SS7 RA P2** | TCAP CONTINUE failover after owning RA death | **RA-wired** today; **production multi-ASP HA** only after lab soak |

## Production-1 — done

| Area | Evidence |
|------|----------|
| Java 25 only | `maven.compiler.release=25`, mise `zulu-25` |
| VT event delivery | `EventDeliveryPortImpl` / virtual-thread entity pool |
| Session routing | `SessionRoutingEngine`, `@ConvergenceName` / repeatable keys |
| Sticky SS7 ownership | ADR 0001 P1: tracker + sticky router + ISPN sticky bus |
| Host adapters | Quarkus / Spring / Jakarta EE + telemetry observers |
| Directory dist | Digicom `dist/<app>/{run.sh,lib,html,configs,logs}` — no WAR |
| Timer default | `LocalTimerAdapter` → Agrona wheel (Netty HashedWheel = compat) |
| Log4j2 | `2.24.3` in BOM |

## Production-2 — open / partial

### Runtime P2 (OffHeap)

| Item | Status |
|------|--------|
| `OffHeapLayout` / `OffHeapArena` / `AgronaOffHeapArena` / `OffHeapCmpAccessor` | **Done** (`jainslee-core`) |
| Deploy-time `$Concrete` via `ConcreteSbbGenerator` (Javassist) | **Done** (`jainslee-codegen`) |
| APT index `@OffHeap` + layout meta | **P2** (see `jainslee-apt`) |
| Example `@OffHeap` SBB | **P2** |
| Soak ≥100k acquire/release | **P2** |
| FFM / MappedByteBuffer | **Later** (not P2) |

### SS7 RA P2 (failover)

| Item | Status |
|------|--------|
| jSS7 `exportDialog` / `importDialog` + `TcapMissingDialogResolver` | **Done** (coral-valley `j25`) |
| RA `Jss7TcapDialogFailoverPort` + ISPN snapshot | **Done** (RA-wired) |
| Failover / sticky-miss / import-fail metrics | **Done** (atomics + `failoverMetrics` on `/api/ra/ra-jss7/status`) |
| Multi-ASP / same-AS lab soak script | **P2** (lab open — not production HA) |
| Invoke tables / MAP dialogue restore | **Open** (ADR gaps) |

### jSS7 Phase 3–5 (adjacent)

| Phase | Status |
|-------|--------|
| 1–2 Java 25 + test modernization | **Done** |
| 3 Javolution in **main** | **Done** (0 `import javolution` under `src/main`) |
| 3 Test XML still on `javolution.xml.*` API | **Debt** (~186 files) → Jackson shim / migrate |
| 4 VT + ZGC audit | Checklist in jSS7 `docs/vt-zgc-audit.md` |
| 5 RA wrapper | **Done** as `vendor-ras/ra-jss7` in this tree |

## Explicit non-goals

- Convert OTA SMSC-GW host off `adapter-quarkus`
- Claim `ss7.live` from LISTEN / `isActive()` / Apply-once
- Squash Flyway V1–V4 in OTA
- Dual Log4j / Quarkus file handlers

## Acceptance pointers

**Runtime P2:** `@OffHeap` SBB indexed by APT; `$Concrete` binds slots; soak N≥100k; heap CMP path unchanged; Log4j2 only.

**SS7 HA P2 (separate claim):** scripted mid-dialog ASP kill → CONTINUE resumes; metrics scrapeable; do **not** call it production HA before multi-ASP soak.
