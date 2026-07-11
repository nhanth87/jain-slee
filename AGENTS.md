# AGENTS.md — Micro-JAINSLEE Runtime

## REPOSITORY INFO

| Repo | Remote | Branch |
|------|--------|--------|
| micro-jainslee | nhanth87 | micro-jainslee-2 |
| jSS7 (Java 25) | origin (nhanth87/jss7) | j25 |
| jSS7 (Java 8) | origin (nhanth87/jss7) | master |
| SCTP | local | java25-upgrade |

## MISSION
Re-architect the micro-jainslee RUNTIME (not the app) to natively support
local/internal RA and SBB development pattern defined in docs/junior-dev-guide.md.

## CONTEXT — READ THESE FILES FIRST (in order)
1. docs/junior-dev-guide.md          ← NEW architecture (source of truth)
2. docs/gap-analysis.md              ← what is already implemented
3. docs/microjainslee-design.md      ← current runtime design
Do NOT start coding until you have read all 3 files.

## CURRENT STATE (runtime modules)
```
jainslee-api        ← Pure Java 25 API (JSR-240)
jainslee-core       ← Engine: MicroSleeContainer, EventRouter,
                       VirtualThreadSbbEntityPool, SleeTimerSchedulerBridge,
                       SbbIndexLoader, ServiceRegistry, SbbTransactionContext,
                       DefaultInitialEventSelector, DefaultErrorHandlingPolicy
jainslee-scheduler  ← Netty HashedWheelTimer (10ms), TimerType.SLEE_TIMER
jainslee-apt        ← APT codegen, GeneratedEventTypes, SbbIndexLoader
adapter-quarkus     ← Quarkus/CDI/GraalVM adapter
adapter-spring      ← Spring Boot adapter
ra-connectors       ← vendor RA stubs
```

## TARGET STATE — what needs to change in the RUNTIME

### GOAL 1: Formalize 3-port contract as first-class API
**File:** `jainslee-api/src/main/java/...`

Ensure these interfaces exist and are stable:
```java
RaEndpointPort    { void activate(RaBootstrapPort); void deactivate(); String getRaName(); }
RaCommandPort     { void sendCommand(OutboundCommand); }
RaBootstrapPort   { ActivityHandle createActivityHandle(String id);
                    void fireEvent(SleeEvent, ActivityHandle, Address); }
OutboundCommand   (marker sealed interface)
```
- If they already exist: verify signatures match exactly the above
- If missing or different: add/fix them — do NOT break existing classes

### GOAL 2: MicroSleeContainer must accept local RA registration
**File:** `jainslee-core/.../MicroSleeContainer.java`
- Add method: `registerRa(RaEndpointPort endpoint, RaCommandPort command)`
- Add method: `mapEventToSbb(Class<? extends SleeEvent> eventType, String sbbName)`
- On container start: call `endpoint.activate(bootstrap)` for each registered RA
- On container stop: call `endpoint.deactivate()` in reverse order
- RaBootstrapPort impl must route `fireEvent()` → EventRouter (existing)

### GOAL 3: SbbIndexLoader must support programmatic SBB registration
**File:** `jainslee-core/.../SbbIndexLoader.java` (or `ServiceRegistry.java`)
- Add method: `registerSbb(Class<? extends Sbb> sbbClass)`
- This supplements (does NOT replace) existing META-INF/services discovery
- Programmatic registration takes priority over descriptor-based

### GOAL 4: Inject RaCommandPort into SBB
- SBB fields annotated with `@InjectRa(name="ra-name")` must be injected
  with the registered RaCommandPort at SBB creation time
- If `@InjectRa` does not exist: create it in `jainslee-api`
- Injection happens inside `VirtualThreadSbbEntityPool.createSbbEntity()`

### GOAL 5: Update ra-connectors module
- Move existing vendor RA stubs to implement new `RaEndpointPort` + `RaCommandPort`
- Keep backward compat — do NOT delete existing classes

## STRICT RULES
- NEVER modify application code — only runtime modules above
- NEVER break existing 62+ tests (run: `mvn test` before and after)
- NEVER add Spring/Quarkus imports into `jainslee-api` or `jainslee-core`
- NEVER use reflection in `jainslee-core` (use APT or ServiceLoader instead)
- Keep `jainslee-core` as Pure Java 25, ZERO framework deps

## EXECUTION ORDER
1. Read 3 docs files
2. Run: `mvn test`  (baseline — must pass)
3. Implement GOAL 1 (api changes)
4. Implement GOAL 2 (container)
5. Implement GOAL 3 (sbb registration)
6. Implement GOAL 4 (ra injection)
7. Implement GOAL 5 (ra-connectors)
8. Run: `mvn test`  (must still pass)
9. Report: list of changed files + any failing tests

## DONE WHEN
- [ ] `mvn test` passes (same count as baseline)
- [ ] `MicroSleeContainer.registerRa()` works
- [ ] `MicroSleeContainer.registerSbb()` works
- [ ] `MicroSleeContainer.mapEventToSbb()` works
- [ ] `RaBootstrapPort.fireEvent()` routes to EventRouter
- [ ] `@InjectRa` injection works in SBB
- [ ] No framework imports in `jainslee-api` or `jainslee-core`

## EXTERNAL PROJECTS (build from these local sources — do NOT re-implement)

### SCTP transport
- **Source:** `/home/meodien/Desktop/ethiopia-working-dir/sctp` (`org.mobicents.protocols.sctp`, branch `java25-upgrade`, version **2.0.14**, Java 25).
- **Use this** for all SCTP — do NOT build a separate/custom SCTP Management. It already
  supports everything needed: `Management.addAssociation(...extraHostAddresses)` /
  `addServer(...)` for multi-homing, XML persistence (`setPersistDir`, Jackson `XmlMapper`).
  Netty impl: `org.mobicents.protocols.sctp.netty.NettySctpManagementImpl`.
- **Gotcha (2026-07-10):** the project compiles under Java 25, but the jar previously in
  `~/.m2` was the stock **Java 8** build. Always `mvn install` this project after changes so
  `.m2` holds the Java-25 build. `ra-jss7` pins sctp `2.0.14` directly (overrides the
  transitive `2.0.16` from `ss7-config`, Maven nearest-wins).

### jSS7 stack (Java 25 line "j25")
- **Source:** `/home/meodien/orca/workspaces/jSS7/j25` (`org.restcomm.protocols.ss7`, branch `j25`, **9.2.8-j25**).
- New module **`ss7-config`** here holds the neutral single-file JSON stack config + the
  `Ss7StackBuilder` compiler (SCTP→M3UA→SCCP→TCAP→MAP/CAP). `ra-jss7` depends on it and stays
  vendor-neutral (no per-layer jSS7 wiring).
