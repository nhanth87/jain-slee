# micro-jainslee — Core Gap Analysis (Jakarta + Quarkus + Java 25)

**Updated:** 2026-06-26  
**Status:** Phases 1–4 implemented on branch `micro-jainslee`

---

## Trạng thái hiện tại

### ✅ ĐÃ IMPLEMENT

| Component | Location |
|-----------|----------|
| EventRouter + priority sort + concurrency modes | `jainslee-core/EventRouter` |
| InitialEventSelector (GAP-19) | `DefaultInitialEventSelector` |
| ErrorHandlingPolicy + rollback (GAP-17) | `DefaultErrorHandlingPolicy` |
| EventContext suspend/resume (GAP-10) | `InMemoryActivityContext` implements `EventContext` |
| ConcurrencyControl (GAP-15) | `ConcurrencyControl` enum + AC locks |
| SbbLocalObject local invoke (GAP-07) | `SimpleSbbLocalObject` + `SbbLocalInvoker` |
| Logical transaction (GAP-09) | `SbbTransactionContext` |
| SbbContext + ServiceID (GAP-11) | `SimpleSbbContext` |
| APT EventType constants (GAP-13) | `GeneratedEventTypes.java` via APT |
| SbbIndexLoader auto-deploy (GAP-14) | `SbbIndexLoader` + `MicroSleeContainer.start()` |
| ServiceRegistry (GAP-02) | `ServiceRegistry` + `ServiceState` |
| RaBootstrapContext (GAP-12) | `RaBootstrapContextImpl` + `RaBootstrap` |
| TracePort levels (GAP-05) | `TraceLevel` + `TraceFacilityQuarkusAdapter` |
| UsagePort samples (GAP-06) | `UsageFacilityQuarkusAdapter` (Micrometer optional) |
| AlarmPort (GAP-04) | `AlarmPort` + Quarkus adapter |
| ProfileTablePort (GAP-01) | `InMemoryProfileTablePort` + Quarkus stub |
| NamingPort (GAP-03) | `InMemoryNamingPort` |
| Timer (vendored scheduler) | `jainslee-scheduler` module |
| Namespace strategy doc (GAP-16) | `docs/microjainslee-design.md` §13 |
| Quarkus native profile (GAP-20) | `adapters/adapter-quarkus/pom.xml` |

### ⏳ DEFERRED (non-goals / production stack)

| Gap | Rationale |
|-----|-----------|
| TCK compliance | R&D only — Mobicents container targets TCK |
| Cluster / HA timers | Single-JVM; Infinispan adapter deferred |
| JSR-77 MBean management | Delegate to Quarkus Actuator / WildFly JMX |
| Full ProfileFacility JPA | Interface + in-memory done; JPA when USSD needs it |
| Full javax.slee.* API parity | micro-jainslee uses `com.microjainslee.api` subset |

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
- `InMemoryActivityContextNamingFacility`

#### GAP-09: Transaction — **DONE (logical)**
- `SbbTransactionContext`, no JTA

#### GAP-10: EventContextInterface — **DONE**
- suspend/resume on `InMemoryActivityContext`

#### GAP-11: SbbContext — **DONE**
- `getService()`, full facility accessors

#### GAP-12: RaBootstrapContext — **DONE**
- `RaBootstrapContextImpl`, `RaBootstrap`

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

#### GAP-19: InitialEventSelector — **DONE**
- `DefaultInitialEventSelector`

#### GAP-20: Quarkus build — **DONE**
- native profile, CI includes scheduler

---

## Test coverage

```
mvn -pl jainslee-api,jainslee-scheduler,jainslee-core,jainslee-apt,ra-connectors,adapters/adapter-quarkus/runtime -am test
→ 62+ tests, BUILD SUCCESS
```

Key test classes: `EventRouterGapTest`, `SbbTransactionRollbackIntegrationTest`, `MicroSleeContainerAutoDeployTest`, `FacilityPortsTest`, `SbbIndexLoaderTest`, `RaBootstrapContextImplTest`
