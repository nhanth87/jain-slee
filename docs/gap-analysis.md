# micro-jainslee — Core Gap Analysis (Jakarta + Quarkus + Java 25)

**Updated:** 2026-06-28
**Status:** Perfect Core S1–S5 done on branch `micro-jainslee` (commit range `HEAD~9..HEAD`).

---

## Implementation status — 2026-06-28

Phases A, B, C from the original gap analysis **plus** all 5 Perfect Core
sprints are now implemented. **Build status: `mvn -pl '!jainslee-cluster'
test` → BUILD SUCCESS.** (Cluster tests take ~60 s and are exercised
separately; use `mvn -pl jainslee-cluster -am test` for the full suite.)

| Component | Location | Status |
|-----------|----------|:---:|
| EventRouter + priority sort + concurrency modes | `jainslee-core/EventRouter` | done |
| InitialEventSelector (GAP-19) | `DefaultInitialEventSelector` | done |
| **Initial Event Selector dispatcher (IES)** — Perfect Core S3 | `core.ies.InitialEventSelectorDispatcher` + `InitialEventSelectCondition` + `InitialEventSelectResult` | done |
| **JTA wiring** — Perfect Core S1 | `jainslee-tx/JtaTransactionManager` + `NoOpTransactionManager` | done |
| **CMP Javassist codegen** — Perfect Core S2 | `jainslee-codegen/ConcreteSbbGenerator` + `JavassistDeployTimeCodegen` | done |
| **Child SBB relations + cascade removal** — Perfect Core S4 | `core.ChildRelation<T>` + `core.child.CascadeRemover` (depth-first post-order) | done |
| **RA full wiring** — Perfect Core S5 | `jainslee-ra-spi/{RaEntityStateMachine, SleeEndpointImpl, ResourceAdaptorContextImpl}` | done |
| **TCK harness** — Perfect Core S6 | `jainslee-tck-harness/{TckRunner, MicrojainsleeContainerAdapter}` | skeleton |
| ErrorHandlingPolicy + rollback (GAP-17) | `DefaultErrorHandlingPolicy` | done |
| EventContext suspend/resume (GAP-10) | `InMemoryActivityContext` implements `EventContext` | done |
| ConcurrencyControl (GAP-15) | `ConcurrencyControl` enum + AC locks | done |
| SbbLocalObject local invoke (GAP-07) | `SimpleSbbLocalObject` + `SbbLocalInvoker` | done |
| Logical transaction (GAP-09) | `SbbTransactionContext` (still kept as logical fallback) | done |
| SbbContext + ServiceID (GAP-11) | `SimpleSbbContext` | done |
| APT EventType constants (GAP-13) | `GeneratedEventTypes.java` via APT | done |
| SbbIndexLoader auto-deploy (GAP-14) | `SbbIndexLoader` + `MicroSleeContainer.start()` | done |
| ServiceRegistry (GAP-02) | `ServiceRegistry` + `ServiceState` | done |
| RaBootstrapContext (GAP-12) | `RaBootstrapContextImpl` + `RaBootstrap` | done |
| TracePort levels (GAP-05) | `TraceLevel` + `TraceFacilityQuarkusAdapter` | done |
| UsagePort samples (GAP-06) | `UsageFacilityQuarkusAdapter` (Micrometer optional) | done |
| AlarmPort (GAP-04) | `AlarmPort` + Quarkus adapter | done |
| ProfileTablePort (GAP-01) | `InMemoryProfileTablePort` + Quarkus stub | done |
| NamingPort (GAP-03) | `InMemoryNamingPort` | done |
| Timer (vendored scheduler) | `jainslee-scheduler` module | done |
| Namespace strategy doc (GAP-16) | `docs/microjainslee-design.md` §13 | done |
| Quarkus native profile (GAP-20) | `adapters/adapter-quarkus/pom.xml` | done |
| **Cluster ACNF (Infinispan DIST_SYNC)** | `jainslee-cluster/ClusteredActivityContextNamingFacility` | done |
| **Distributed SBB entity pool** | `jainslee-cluster/DistributedSbbEntityPool` (snapshot/replication) | done |

### Perfect Core S1–S5 (June 2026)

| Sprint | Commit | Module | Why it matters |
|--------|--------|--------|----------------|
| **S1 — JTA** | [`ae3666a89`](https://github.com/nhanth87/micro-jainslee/commit/ae3666a89) | **new** `jainslee-tx` | Every `EventRouter.deliverEvent` now runs in a Narayana 7.0 JTA transaction. `NoOpTransactionManager` is the R&D fallback. |
| **S2 — CMP codegen** | [`a7566ed29`](https://github.com/nhanth87/micro-jainslee/commit/a7566ed29) | **new** `jainslee-codegen` | `ConcreteSbbGenerator` materializes concrete SBB classes with backing fields + `ChildRelation` accessors. Javassist `ClassPool` cached per source class. |
| **S3 — IES** | [`37c7e4c36`](https://github.com/nhanth87/micro-jainslee/commit/37c7e4c36) | `jainslee-core` + `jainslee-api` | New `@InitialEventSelect` annotation. `EventRouter.routeIncomingEvent` resolves the *convergence key* (e.g. `msisdn:dialogId`) to the existing SBB entity — fixes broken USSD/SIP-dialog state. |
| **S4 — Child relations** | [`05cefe3dc`](https://github.com/nhanth87/micro-jainslee/commit/05cefe3dc) | `jainslee-core` + `jainslee-api` | `ChildRelation<T>` + iterative `CascadeRemover` walk. Used by `SimpleSbbLocalObject.remove()` and container shutdown. |
| **S5 — RA** | [`a2029f26d`](https://github.com/nhanth87/micro-jainslee/commit/a2029f26d) | `jainslee-ra-spi` | Full RA framework: `RaEntityStateMachine` (INACTIVE→ACTIVE→STOPPING→INACTIVE), `SleeEndpointImpl` (validated fire), `ResourceAdaptorContextImpl` (Timer/Alarm/Trace/ACNF/EventLookup). |
| **S6 — TCK** | [`0b4210f08`](https://github.com/nhanth87/micro-jainslee/commit/0b4210f08) | **new** `jainslee-tck-harness` | TCK harness skeleton (`TckRunner`, `MicrojainsleeContainerAdapter`). Only 5 of 13 JAIN SLEE 1.1 groups wired — coverage grows per sprint. |

Wiring reference: [`docs/WIRING_GUIDE.md`](WIRING_GUIDE.md). Each sprint
section in that doc lists the exact files that needed changes.

### Verification command (Perfect Core)

```bash
# 2026-06-28 — 81 tests pass, 0 failures
mvn -pl jainslee-api,jainslee-scheduler,jainslee-core,jainslee-tx,jainslee-codegen,jainslee-ra-spi,jainslee-apt -am test
```

Key test classes:
`EventRouterGapTest`, `SbbTransactionRollbackIntegrationTest`,
`MicroSleeContainerAutoDeployTest`, `FacilityPortsTest`,
`SbbIndexLoaderTest`, `RaBootstrapContextImplTest`,
`InitialEventSelectorDispatcherTest`, `CascadeRemoverTest`,
`ChildRelationImplTest`, `UssdIesEndToEndTest`,
`JtaTransactionManagerTest`, `JtaIntegrationStressTest`,
`ConcreteSbbGeneratorTest`, `JavassistDeployTimeCodegenTest`.

---

## ⏳ DEFERRED (non-goals / production stack)

| Gap | Rationale |
|-----|-----------|
| TCK compliance | R&D only — Mobicents container targets TCK; jainslee-tck-harness is foundation only |
| Cluster / HA timers | Single-JVM timers; Infinispan-backed `FaultTolerantTimer` deferred |
| JSR-77 MBean management | Delegate to Quarkus Actuator / WildFly JMX |
| Full ProfileFacility JPA | Interface + in-memory done; JPA when USSD needs it |
| Full javax.slee.* API parity | micro-jainslee uses `com.microjainslee.api` subset |
| CMP Javassist on hot path | Build-time codegen (S2) generates concrete classes; runtime `CtClass.toClass()` is never called |

---

## Gap detail (original analysis)

#### GAP-01: ProfileFacility — **DONE (minimal)**
- `ProfileTablePort` + `InMemoryProfileTablePort` + Quarkus adapter stub

#### GAP-02: ServiceManagement — **DONE (minimal)**
- `ServiceRegistry`, `ServiceState`, lifecycle activate/stop

#### GAP-03: NamingFacility — **DONE (minimal)**
- `NamingPort` + `InMemoryNamingPort`

#### GAP-04: AlarmFacility — **DONE**
- `AlarmPort`, `AlarmLevel`, Quarkus adapter

#### GAP-05: TraceFacility — **DONE**
- `TraceLevel`, `TraceFacilityQuarkusAdapter`

#### GAP-06: UsageFacility — **DONE**
- Extended `UsagePort`, Micrometer adapter

#### GAP-07: SbbLocalObject — **DONE**
- priority, remove, invokeLocally via entity pool

#### GAP-08: ActivityContextNamingFacility — **DONE (pre-existing)**
- `InMemoryActivityContextNamingFacility`; cluster variant in `jainslee-cluster`

#### GAP-09: Transaction — **DONE (logical + JTA)**
- `SbbTransactionContext` (logical fallback) **and** `JtaTransactionManager` (Narayana, Perfect Core S1)

#### GAP-10: EventContextInterface — **DONE**
- suspend/resume on `InMemoryActivityContext`

#### GAP-11: SbbContext — **DONE**
- `getService()`, full facility accessors

#### GAP-12: RaBootstrapContext — **DONE**
- `RaBootstrapContextImpl`, `RaBootstrap` (kept as compatibility shim over the S5 state machine)

#### GAP-13: EventTypeRepository — **DONE**
- APT generates `GeneratedEventTypes.java`

#### GAP-14: DeployableUnit — **DONE**
- `SbbIndexLoader`, auto-register on start

#### GAP-15: ConcurrencyControl — **DONE**
- Three modes mapped to AC locking

#### GAP-16: Namespace strategy — **DONE**
- Documented in design doc §13

#### GAP-17: Error handling — **DONE**
- `ErrorHandlingPolicy` + rollback

#### GAP-18: SBB Priority — **DONE**
- Priority sort in `EventRouter`

#### GAP-19: InitialEventSelector — **DONE (upgraded S3)**
- `DefaultInitialEventSelector` (pre-existing) **plus** `InitialEventSelectorDispatcher` (Perfect Core S3)

#### GAP-20: Quarkus build — **DONE**
- native profile, CI includes scheduler

---

## Test coverage

```
mvn -pl '!jainslee-cluster' -am test   → 81+ tests, BUILD SUCCESS
```

Key test classes: `EventRouterGapTest`, `SbbTransactionRollbackIntegrationTest`,
`MicroSleeContainerAutoDeployTest`, `FacilityPortsTest`,
`SbbIndexLoaderTest`, `RaBootstrapContextImplTest`,
`InitialEventSelectorDispatcherTest`, `CascadeRemoverTest`,
`ChildRelationImplTest`, `UssdIesEndToEndTest`,
`JtaTransactionManagerTest`, `JtaIntegrationStressTest`,
`ConcreteSbbGeneratorTest`, `JavassistDeployTimeCodegenTest`,
`VirtualThreadSbbEntityPoolTest`, `SbbEntityPoolStressTest`.
