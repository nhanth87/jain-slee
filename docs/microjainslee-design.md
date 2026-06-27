# micro-jainslee 1.1.0 — Design Document

> **Audience:** engineers embedding micro-jainslee in **Quarkus 3** applications;
> contributors extending the container; reviewers evaluating the architecture.
> Spring Boot and Jakarta EE adapters follow the same core patterns described here.

**Maintainer:** Tran Nhan ([nhanth87@gmail.com](mailto:nhanth87@gmail.com))  
**License:** Dual — GPLv3 OR Commercial (see [`LICENSE`](../LICENSE))  
**Last updated:** 2026-06-26  
**Source:** [`jain-slee/`](../../) (branch `micro-jainslee`)

---

## Table of contents

1. [Goals and non-goals](#1-goals-and-non-goals)
2. [Architectural overview](#2-architectural-overview)
3. [Module map](#3-module-map)
4. [Event router pipeline](#4-event-router-pipeline)
5. [SBB entity pool with Java 25 virtual threads](#5-sbb-entity-pool-with-java-25-virtual-threads)
6. [Timer facility and jSS7 bridge](#6-timer-facility-and-jss7-bridge)
7. [Annotation processor](#7-annotation-processor)
8. [Runtime integrations](#8-runtime-integrations)
9. [Configuration model](#9-configuration-model)
10. [Concurrency contract](#10-concurrency-contract)
11. [Failure modes and recovery](#11-failure-modes-and-recovery)
12. [Design decisions log](#12-design-decisions-log)
13. [Namespace strategy (GAP-16)](#13-namespace-strategy-gap-16)

---

## 1. Goals and non-goals

### Goals

| Goal | How we meet it |
|---|---|
| Run the JAIN SLEE 1.1 spec semantics inside a plain JVM | Pure JDK 8 source/target, no JBoss Modules, VFS, MSC, JMX dependency |
| Embeddable in popular Java frameworks | First-class adapters for Spring Boot 3, Quarkus 3, Jakarta EE 9 |
| Scale to 100K concurrent SBBs on commodity hardware | Per-SBB virtual-thread pinning (Java 25); 14 OS threads host 100K virtual threads in our stress test |
| Stay under 5 KLOC | Current count: 2,528 LOC in `jainslee-core`, ~3,000 LOC across the whole micro-jainslee reactor |
| Be readable end-to-end in one sitting | One class per concern, one file per responsibility, no hidden codegen on the hot path |

### Non-goals

| Non-goal | Rationale |
|---|---|
| TCK compliance | micro-jainslee is R&D-only; production USSD 7.3 still ships the JBoss/Mobicents stack which is the TCK-targeted implementation |
| Cluster / HA | Single-JVM only. Clustered deployment would require adding an Infinispan-backed timer and replicated SBB state, which the production stack already provides |
| JSR-77 management | The embedding runtime (Spring Boot Actuator, Quarkus SmallRye Health, WildFly JMX) is responsible for management surface |
| JTA transactions | SBBs can wrap their own logic in Spring `@Transactional` or Quarkus `@Transactional`; micro-jainslee itself has no transaction manager |
| Congestion control | The embedding application is responsible for rate limiting; the EventRouter drops nothing |

---

## 2. Architectural overview

```mermaid
flowchart TB
    subgraph adapters["Runtime integration (pick one)"]
        direction LR
        Q["Quarkus 3<br/>adapter-quarkus"]
        SB["Spring Boot 3<br/>jainslee-spring-boot-starter"]
        EE["Jakarta EE 9<br/>adapter-jakartaee"]
    end

    subgraph core["MicroSleeContainer — com.microjainslee.core"]
        ER["EventRouter<br/>LMAX Disruptor"]
        TP["TimerPortImpl<br/>+ SleeTimerSchedulerBridge"]
        ACNF["InMemoryActivityContextNamingFacility"]
        POOL["VirtualThreadSbbEntityPool<br/>1 parked VT per SBB ID"]
    end

    subgraph api["jainslee-api — spec contracts only"]
        SPEC["Sbb · SbbContext · TimerPort<br/>ActivityContextInterface · ResourceAdaptor"]
    end

    classDef adapter fill:#e3f2fd,stroke:#1565c0,color:#0d47a1
    classDef container fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef spec fill:#f3e5f5,stroke:#7b1fa2,color:#4a148c

    class Q,SB,EE adapter
    class ER,TP,ACNF,POOL container
    class SPEC spec

    Q --> core
    SB --> core
    EE --> core
    core --> api
```

There are exactly **four orthogonal concerns**:

1. **Event routing** — `EventRouter` owns a single LMAX Disruptor ring
   buffer. Producers (RA callbacks, `MicroSleeContainer.routeEvent`,
   timer fires) publish wrapped events into the ring; one consumer
   dispatches to the ACI's attached SBBs.
2. **SBB threading** — `VirtualThreadSbbEntityPool` pins each SBB ID to
   its own parked virtual thread. Every `entity.submit(runnable)` lands
   on that one thread, giving the spec-mandated single-threaded
   per-SBB ordering.
3. **Timer facility** — `TimerPortImpl` delegates to
   `SleeTimerSchedulerBridge` which schedules a `TimerRecord` on jSS7's
   `LocalTimerAdapter` (Netty `HashedWheelTimer`, 10 ms tick). When the
   wheel fires, the bridge posts a `TimerFiredEvent` back through
   `EventRouter.routeEvent` — **never** invokes SBB code on the
   hashed-wheel thread.
4. **Naming & lookup** — `InMemoryActivityContextNamingFacility` is a
   `ConcurrentHashMap<String, ActivityContextInterface>`; an RA binds a
   new ACI under a string name and any SBB can look it up.


---

## 3. Module map

| Maven coordinates | Purpose | Public API surface |
|---|---|---|
| `com.microjainslee:jainslee-api:1.1.0` | Spec contracts only — zero implementation | `Sbb`, `SbbContext`, `SbbLocalObject`, `SbbID`, `SleeEvent`, `SleeEventHandler`, `TimerPort`, `TimerFiredEvent`, `ActivityContextInterface`, `ActivityContextNamingFacility`, `ResourceAdaptor`, `ResourceAdaptorContext`, `TracePort`, `TraceLevel`, `UsagePort`, `AlarmPort`, `AlarmLevel`, `ProfileTablePort`, `NamingPort`, `EventTypeRef`, `@SbbAnnotation`, `@DeployableUnit`, `@EventType` |
| `com.microjainslee:jainslee-core:1.1.0` | Embedded container — the runtime | `MicroSleeContainer`, `MicroSleeConfiguration` (+ Builder), `MicroSleeExecutors`, `EventRouter`, `VirtualThreadSbbEntityPool` (+ nested `SbbEntity`), `SbbEntityPool` (shim), `TimerPortImpl`, `SleeTimerSchedulerBridge`, `SbbLifecycleManager`, `InMemoryActivityContext`, `InMemoryActivityContextNamingFacility`, `SimpleSbbContext`, `SimpleSbbLocalObject`, `SimpleTracePort`, `SimpleUsagePort`, `SimpleAlarmPort`, `InMemoryProfileTablePort`, `InMemoryNamingPort` |
| `com.microjainslee:jainslee-apt:1.1.0` | Javac annotation processor — emits `META-INF/microjainslee/sbb-index.properties` at compile time | `MicroJainsleeAnnotationProcessor` + SPI under `META-INF/services/javax.annotation.processing.Processor` |
| `com.microjainslee:jainslee-spring-boot-starter:1.1.0` | Spring Boot 3 auto-configuration | `@AutoConfiguration MicroJainsleeAutoConfiguration`, `@ConfigurationProperties MicroJainsleeProperties`, `MicroJainsleeLifecycle`, `MicroJainsleeDeployer`, `@EnableMicroJainslee` |
| `com.microjainslee:adapter-quarkus:1.1.0` (3-module reactor: parent + runtime + deployment) | Quarkus 3 extension | `MicroJainsleeBuildConfig`, `MicroJainsleeProcessor`, `MicroJainsleeRecorder`, `MicroJainsleeProducer`, `MicroJainsleeHolder`, `TraceFacilityQuarkusAdapter`, `UsageFacilityQuarkusAdapter`, `AlarmPortQuarkusAdapter`, `ProfileTablePortQuarkusAdapter` |
| `com.microjainslee:adapter-jakartaee:1.1.0` | Jakarta EE 9 EJB integration | `@Singleton @Startup @LocalBean MicroSleeContainerStartup`, `JndiNames` |

Dependencies flow strictly one-way: adapters depend on core; core depends on api; api has no dependencies. Example apps ship their own RA classes (see `example/example-quarkus/.../ra/`).

```
                        ┌───────────────┐
                        │ jainslee-api  │  (no deps)
                        └───────┬───────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
┌───────▼───────┐   ┌───────────▼───────────┐
│ jainslee-core │   │   jainslee-apt       │
│  (Disruptor   │   │  (javax.annotation  │
│   + pools)    │   │   .processing)      │
└───────┬───────┘   └─────────────────────┘
        │
        ├──────────────────────┬───────────────────────┐
        │                      │                       │
┌───────▼────────────┐ ┌───────▼─────────────┐ ┌───────▼──────────────┐
│ jainslee-spring-   │ │ adapter-quarkus    │ │ adapter-jakartaee   │
│ boot-starter       │ │  (parent + runtime │ │  (@Singleton EJB +  │
│  (Spring Boot 3)   │ │   + deployment)    │ │   JNDI bind)        │
└────────────────────┘ └─────────────────────┘ └──────────────────────┘
```

---

## 4. Event router pipeline

The single hot path of the runtime is event delivery:

```
producer → ringBuffer.next() → ringBuffer.publish() → consumer fires → dispatch → SbbEntity.submit() → parked VT runs sbb.onEvent()
```

### End-to-end sequence

```mermaid
sequenceDiagram
    autonumber
    participant Prod as Producer (RA / timer / test)
    participant ER as EventRouter
    participant RB as Disruptor ring buffer
    participant POOL as VirtualThreadSbbEntityPool
    participant VT as parked virtual thread
    participant SBB as Sbb.onEvent()

    classDef producer fill:#e3f2fd,stroke:#1565c0,color:#0d47a1
    classDef router fill:#fff3e0,stroke:#ef6c00,color:#e65100
    classDef sbb fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20

    class Prod producer
    class ER,RB router
    class POOL,VT,SBB sbb

    Prod->>ER: routeEvent(SleeEvent, ACI)
    ER->>RB: next() — claim sequence slot
    ER->>RB: get(seq).setEvent/setAci
    ER->>RB: publish(seq) — lock-free barrier
    RB-->>ER: onEvent(EventWrapper) — consumer thread
    ER->>ER: dispatch(event, aci)
    loop for each SBB attached to ACI
        ER->>POOL: entity.submit(Runnable)
        POOL->>VT: queue.offer(task)
        VT->>SBB: task.run() → onEvent(event, aci)
    end
    ER->>RB: wrapper.clear() — reuse slot
```

### Key invariants

- **Single Disruptor instance** — one ring buffer per container, not per
  ACI. Routing is keyed on the ACI's attached SBB IDs.
- **Multi-producer, single-consumer** — `ProducerType.MULTI` lets RAs,
  timers, and tests publish concurrently; `YieldingWaitStrategy`
  yields when the ring is empty rather than spinning.
- **No allocations on the hot path** — `EventWrapper` is reused via
  `ringBuffer.get(seq)`; `wrapper.clear()` resets both fields to
  `null` after dispatch so the GC doesn't churn.

### Sequence (producer side)

```java
public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
    long sequence = ringBuffer.next();
    try {
        EventWrapper wrapper = ringBuffer.get(sequence);
        wrapper.setEvent(event);
        wrapper.setAci(aci);
    } finally {
        ringBuffer.publish(sequence);
    }
}
```

`ringBuffer.publish()` is a lock-free single-word store on the LMAX
sequence barrier. The consumer thread sees the event within a bounded
number of cycles.

### Sequence (consumer side)

```java
this.disruptor.handleEventsWith(new EventHandler<EventWrapper>() {
    public void onEvent(EventWrapper wrapper, long sequence, boolean endOfBatch) {
        try {
            dispatch(wrapper.event, wrapper.aci);
        } finally {
            wrapper.clear();
        }
    }
});
```

`dispatch()` looks up every SBB attached to the ACI and asks the
`VirtualThreadSbbEntityPool` for each SBB ID's parked virtual thread
(via `entity.submit(runnable)`). The handler returns immediately —
the actual `sbb.onEvent()` invocation happens asynchronously on the
parked VT.

### Why pinned virtual threads beat thread-pool executors

A traditional executor (e.g. `ThreadPoolExecutor` with N workers)
would queue an event for an arbitrary worker. Two events for the same
SBB could land on different workers in any order, breaking the
single-threaded per-SBB invariant. With virtual threads we pay ~1 KB
per parked VT and pin each SBB ID to its own VT — no queue, no
contention, no locks.


---

## 5. SBB entity pool with Java 25 virtual threads

`VirtualThreadSbbEntityPool` is the heart of the SBB runtime. Each
registered SBB ID owns one parked virtual thread that sequentially
drains an internal `LinkedBlockingQueue<Runnable>`. Every event handed
to the SBB is enqueued onto that thread, giving the **single-threaded
per-SBB ordering** the JAIN SLEE spec mandates.

### Lifecycle of one entity

```mermaid
sequenceDiagram
    autonumber
    participant Caller as acquire() caller
    participant Pool as VirtualThreadSbbEntityPool
    participant Map as ConcurrentHashMap
    participant VT as new virtual thread
    participant Q as LinkedBlockingQueue

    classDef pool fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef thread fill:#f3e5f5,stroke:#7b1fa2,color:#4a148c

    class Pool,Map pool
    class VT,Q,Caller thread

    Caller->>Pool: acquire(sbbId, factory)
    Pool->>Map: putIfAbsent(sbbId, freshEntity)
    alt first registrant (winner)
        Map-->>Pool: null (inserted)
        Pool->>VT: owner.submit(EventLoop)
        Pool-->>Caller: fresh SbbEntity
    else concurrent race (loser)
        Map-->>Pool: prior entity
        Pool->>Pool: fresh.markShutdown(); queue.clear()
        Pool-->>Caller: prior SbbEntity
    end

    Caller->>Pool: entity.submit(runnable)
    Pool->>Q: offer(runnable)
    VT->>Q: poll(50ms)
    VT->>VT: runnable.run()

    Caller->>Pool: shutdown()
    Pool->>Q: offer(POISON) per entity
    Pool->>VT: awaitTermination(5s)
```

### EventLoop pseudo-code

```java
while (!shutdown.get()) {
    Runnable task = queue.poll(50, TimeUnit.MILLISECONDS);
    if (task == null) {
        parked.set(true);
        if (shutdown.get()) return;
        continue;
    }
    parked.set(false);
    try { task.run(); } catch (Throwable ignored) { /* swallow */ }
}
```

Two key invariants:

1. **Single-threaded per ID** — only this VT ever reads `task` and calls
   `sbb.onEvent(...)`. The SBB sees strict FIFO order across all RA
   events, timer fires, and RA-sourced events.
2. **No leak on exception** — a thrown exception in one task does not
   kill the loop; the next event is still processed.

### Performance characteristics

Measured on **Java 25.0.3 (Azul Zulu 25.34.17)**, 4 CPU cores, 8 GB heap,
default GC:

| Scale | Create (ms) | Pending (ms) | Cancel (ms) | Heap Δ | OS threads |
|------:|------------:|-------------:|-----------:|-------:|-----------:|
| 10K   |     172     |     292      |    106     | +20 MB |    14      |
| 50K   |     970     |   1,828      |    361     | +158 MB|    14      |
| 100K  |   1,302     |   3,650      |    779     | +290 MB|    14      |

`liveThreads` stays at **14 across all three scales** — that's
approximately 4 cores × ~3.5 carrier threads (ForkJoinPool.commonPool()
default). The 100K virtual threads are scheduled on those 14 OS threads
without any of them blocking.

Throughput per pending task: **~7.3 µs/task** for 50K and 100K scales.
The per-SBB `LinkedBlockingQueue.poll(50ms)` is the hot path; it runs
entirely in user space.

---

## 6. Timer facility and jSS7 bridge

`TimerPort` is the JAIN SLEE timer API: `setTimer(ms, sbb)` returns an
opaque `long timerId`; `cancelTimer(timerId)` cancels by id. In
micro-jainslee the implementation is `TimerPortImpl` which delegates
to `SleeTimerSchedulerBridge`.

### Bridge flow

```mermaid
sequenceDiagram
    autonumber
    participant SBB as Sbb (parked VT)
    participant TP as TimerPortImpl
    participant BR as SleeTimerSchedulerBridge
    participant JSS7 as LocalTimerAdapter
    participant ER as EventRouter
    participant VT as same parked VT

    classDef sbb fill:#f3e5f5,stroke:#7b1fa2,color:#4a148c
    classDef timer fill:#fce4ec,stroke:#c62828,color:#880e4f
    classDef router fill:#fff3e0,stroke:#ef6c00,color:#e65100

    class SBB,VT sbb
    class TP,BR,JSS7 timer
    class ER router

    SBB->>TP: setTimer(delayMs, sbbLocal)
    TP->>BR: schedule(sbbLocal, delayMs)
    BR->>BR: resolve ACI, build TimerRecord
    BR->>JSS7: schedule(record, delay, callback)
    Note over JSS7: HashedWheelTimer — never calls SBB here

    JSS7-->>BR: onTimerFire(record) — wheel thread
    BR->>BR: targets.remove(timerId)
    BR->>ER: routeEvent(TimerFiredEvent, aci)
    ER->>VT: entity.submit(onEvent task)
    VT->>SBB: onEvent(timerEvent, aci)
```

### Function call chain

| Step | Caller | Method | Callee |
|-----:|--------|--------|--------|
| 1 | SBB | `TimerPort.setTimer(ms, sbbLocal)` | `TimerPortImpl` |
| 2 | `TimerPortImpl` | `schedule(sbbLocal, ms)` | `SleeTimerSchedulerBridge` |
| 3 | Bridge | `scheduler.schedule(record, ms, callback)` | jSS7 `LocalTimerAdapter` |
| 4 | Wheel thread | `onTimerFire(record)` | Bridge callback |
| 5 | Bridge | `eventRouter.routeEvent(TimerFiredEvent, aci)` | `EventRouter` |
| 6 | `EventRouter` | `entity.submit(() -> onEvent(...))` | `VirtualThreadSbbEntityPool` |

### Why we never invoke SBB code on the wheel thread

If the SBB threw an exception or stalled, calling it directly on the
hashed-wheel thread would freeze every other timer in the system. By
funneling the fire through `EventRouter.routeEvent`, the wheel thread
returns within microseconds and the SBB callback runs on the same
parked virtual thread that originally called `setTimer` — preserving
both order and isolation.

### Timer type — `TimerType.TCAP_INVOKE_TIMEOUT`

The bridge currently reuses jSS7's `TCAP_INVOKE_TIMEOUT` because
jSS7 9.4.0 does not yet expose a generic SLEE timer type. This is a
known R&D limitation — timer semantics are correct for single-JVM
Quarkus embeddings; a dedicated `SLEE_TIMER` enum in jSS7 would remove
the type-name mismatch without changing the bridge contract.


---

## 7. Annotation processor

`jainslee-apt` ships a Javac annotation processor that runs during the
`compile` phase of any project that depends on it. It scans for three
annotations and writes `META-INF/microjainslee/sbb-index.properties`
that the runtime can read on startup.

### Supported annotations

| Annotation | Target | Collected fields |
|---|---|---|
| `@SbbAnnotation(name, vendor, version)` | `ElementType.TYPE` | FQN, name, vendor, version |
| `@EventType(name, vendor, version)` | `ElementType.TYPE` | FQN, name, vendor, version |
| `@DeployableUnit(name, vendor, version, sbbs, ras, profileSpecs)` | `ElementType.TYPE` + `ElementType.PACKAGE` | FQN, name, vendor, version, comma-separated FQN lists of bundled components |

### Why a build-time index instead of runtime scanning

A runtime classpath scan is **O(N)** on every boot. The APT runs once at
compile time, when the class set is already known, and emits a flat
`Properties` file that any thread can read with `getProperty` in
**O(1)**. The runtime never sees a `Class.forName` call.

### Output format

```properties
#micro-jainslee index — generated by MicroJainsleeAnnotationProcessor
sbb.0.class=com.foo.MySbb
sbb.0.name=MySbb
sbb.0.vendor=com.microjainslee
sbb.0.version=1.0
eventType.0.class=com.foo.MyEvent
du.0.class=com.foo.MyDU
du.0.sbbs=com.foo.MySbb
```

### SPI registration

`META-INF/services/javax.annotation.processing.Processor` contains a
single line:

```
com.microjainslee.apt.MicroJainsleeAnnotationProcessor
```

This is the JDK's standard SPI for annotation processors; any `javac`
run with the processor jar on the `-processorpath` will discover and
invoke it automatically.

### Multi-round merge semantics

The processor accumulates discovered annotations across rounds and only
writes the index file on the **final round** (`processingOver()`).
Multi-round compilation (annotation processing → SBB codegen →
final compile) is handled by merging the existing properties file with
new entries and incrementing the index counters (`sbb.0`, `sbb.1`,
…) so concurrent builds don't clobber each other.

---

## 8. Runtime integrations

### 8.1 Spring Boot 3

`jainslee-spring-boot-starter` provides:

- `@AutoConfiguration MicroJainsleeAutoConfiguration` — wires `MicroSleeContainer`,
  `MicroSleeConfiguration`, `EventRouter`, `TimerPort`,
  `InMemoryActivityContextNamingFacility` as Spring beans.
- `@ConfigurationProperties MicroJainsleeProperties` — driven by
  `microjainslee.*` keys in `application.yml`.
- `MicroJainsleeLifecycle implements SmartLifecycle` — calls
  `container.start()` on Spring context refresh,
  `container.stop()` on shutdown. Phase `Integer.MIN_VALUE + 100`
  ensures the SLEE container starts before user beans that may depend
  on its facilities.
- `MicroJainsleeDeployer implements ApplicationRunner` — Phase 3.5 will
  implement the full classpath scanner; the Phase 3 scope only logs
  the requested scan paths.
- `@EnableMicroJainslee` — marker annotation for opt-in use.

### 8.2 Quarkus 3 (primary integration path)

**Quarkus is the recommended runtime** for new micro-jainslee services.
The `adapter-quarkus` extension is a standard 3-module Quarkus layout:

| Module | Artifact role | Key classes |
|--------|---------------|-------------|
| `adapter-quarkus` (parent) | BOM / reactor | — |
| `deployment` | Build-time `@BuildStep` processor | `MicroJainsleeProcessor`, `MicroJainsleeBuildConfig` |
| `runtime` | Run-time recorder + CDI producer | `MicroJainsleeRecorder`, `MicroJainsleeProducer` |

#### Extension bootstrap sequence

```mermaid
sequenceDiagram
    autonumber
    participant Q as Quarkus build
    participant Proc as MicroJainsleeProcessor
    participant CFG as MicroJainsleeBuildConfig
    participant Rec as MicroJainsleeRecorder
    participant CT as MicroSleeContainer
    participant CDI as Arc CDI

    rect rgb(227, 242, 253)
        Note over Q,Rec: STATIC_INIT — augmentation
    Q->>Proc: feature() → FeatureBuildItem("micro-jainslee")
    Q->>Proc: containerConfig(config)
    Proc->>CFG: bufferSize, sbbPoolMin/Max, preferVT, perVT
    Proc-->>Q: MicroSleeConfiguration
    Q->>Proc: installContainer(recorder, configuration)
    Proc->>Rec: createContainer(configuration)
    Rec->>CT: new MicroSleeContainer(config)
    Rec->>Rec: static fields ← container, EventRouter, TimerPort, ACNF
    end

    rect rgb(232, 245, 233)
        Note over Q,CDI: RUNTIME_INIT — application start
    Q->>Proc: startContainer(recorder)
    Proc->>Rec: startContainer()
    Rec->>CT: start()
    Q->>Proc: synthetic beans (container, router, timer, ACNF)
    Proc->>CDI: ApplicationScoped RuntimeValues
    Q->>Proc: sbbSyntheticBeans (if scan enabled)
    Proc->>CDI: register @SbbAnnotation classes from Jandex
    end

    rect rgb(255, 243, 224)
        Note over Q,CT: Shutdown
    Q->>Proc: shutdownContainer(recorder, shutdown)
    Proc->>Rec: stopContainer() via ShutdownContextBuildItem
    Rec->>CT: stop()
    end
```

#### Build-step function call flow

| Phase | `MicroJainsleeProcessor` method | Delegates to |
|-------|--------------------------------|--------------|
| Build | `containerConfig(MicroJainsleeBuildConfig)` | `MicroSleeConfiguration.builder()` |
| `STATIC_INIT` | `installContainer(recorder, configuration)` | `recorder.createContainer(configuration)` |
| `RUNTIME_INIT` | `startContainer(recorder)` | `recorder.startContainer()` → `container.start()` |
| `RUNTIME_INIT` | `containerSyntheticBean(...)` | `SyntheticBeanBuildItem` for `MicroSleeContainer` |
| `RUNTIME_INIT` | `eventRouterSyntheticBean(...)` | Synthetic bean for `EventRouter` |
| `RUNTIME_INIT` | `timerPortSyntheticBean(...)` | Synthetic bean for `TimerPort` |
| `RUNTIME_INIT` | `acnfSyntheticBean(...)` | Synthetic bean for `InMemoryActivityContextNamingFacility` |
| Build | `sbbSyntheticBeans(...)` | Jandex scan for `@SbbAnnotation` implementors |
| `RUNTIME_INIT` | `shutdownContainer(recorder, shutdown)` | `shutdown.addShutdownTask(recorder::stopContainer)` |

#### Dependency in a Quarkus application

```xml
<dependency>
  <groupId>com.microjainslee</groupId>
  <artifactId>adapter-quarkus</artifactId>
  <version>1.1.0</version>
</dependency>
```

User services inject the container and facilities:

```java
@ApplicationScoped
public class UssdEntryPoint {
    @Inject MicroSleeContainer container;
    @Inject EventRouter eventRouter;
    @Inject TimerPort timerPort;

    public void onSessionStart(String sessionId, SleeEvent event) {
        ActivityContextInterface aci = container.createActivityContext(sessionId);
        SbbLocalObject sbb = container.registerSbb("UssdMenuSbb", new UssdMenuSbb());
        container.attach(sessionId, sbb);
        container.routeEvent(event, aci);
    }
}
```

`MicroJainsleeProducer` also exposes `@Produces @DefaultBean` methods so
applications can override any facility with a CDI `@Alternative`.

The processor uses `jakarta.enterprise.context.ApplicationScoped` and
`jakarta.enterprise.inject.Produces` (NOT `javax.*`) — Quarkus 3.x is
on the Jakarta EE 9+ namespace.

### 8.3 Jakarta EE 9

`adapter-jakartaee` ships a single `@Singleton @Startup @LocalBean`
EJB:

```java
@Singleton @Startup @LocalBean
public class MicroSleeContainerStartup {
    @PostConstruct void init()    { container.start();   bindAll();   }
    @PreDestroy    void shutdown(){ container.stop();    unbindAll(); }
}
```

It binds the four facilities into JNDI at `java:global/microjainslee/*`
via `InitialContext.bind` / `rebind` (handle `NameAlreadyBoundException`
gracefully). Configuration comes from system properties prefixed with
`microjainslee.config.*`. The adapter uses `jakarta.ejb.*`,
`jakarta.annotation.*`, and `javax.naming.*` — Jakarta EE 9+ mandated
the jakarta.* rename for EJB and annotations, but JNDI stayed in
javax.* because Jakarta EE 9 left JNDI for a later release.


---

## 9. Configuration model

`MicroSleeConfiguration` is an immutable value object built via a
fluent `Builder`. Defaults:

| Field | Default | Validation |
|---|---:|---|
| `eventRouterBufferSize` | 1024 | must be a positive power of two |
| `preferVirtualThreads` | true | none |
| `sbbPoolMin` | 16 | `>= 0` and `<= sbbPoolMax` |
| `sbbPoolMax` | 1024 | `>= 1` |
| `sbbPerVirtualThread` | true | none |

### Builder usage

```java
MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
        .eventRouterBufferSize(8192)
        .preferVirtualThreads(true)
        .sbbPoolMin(64)
        .sbbPoolMax(100_000)
        .build();
```

Validation runs in `build()` (not in setters), so the order of calls
does not matter — you can set `sbbPoolMax` before `sbbPoolMin` without
triggering a false invalid state.

### Spring Boot property mapping

| Property | `MicroSleeConfiguration` field |
|---|---|
| `microjainslee.event-router.buffer-size` | `eventRouterBufferSize` |
| `microjainslee.event-router.prefer-virtual-threads` | `preferVirtualThreads` |
| `microjainslee.sbb-pool.min` | `sbbPoolMin` |
| `microjainslee.sbb-pool.max` | `sbbPoolMax` |
| `microjainslee.sbb-pool.per-virtual-thread` | `sbbPerVirtualThread` |

### Quarkus property mapping (`@ConfigMapping("microjainslee")`)

| Property | Field |
|---|---|
| `microjainslee.buffer-size` | `bufferSize` |
| `microjainslee.prefer-virtual-threads` | `preferVirtualThreads` |
| `microjainslee.sbb-pool-min` | `sbbPoolMin` |
| `microjainslee.sbb-pool-max` | `sbbPoolMax` |
| `microjainslee.sbb-per-virtual-thread` | `sbbPerVirtualThread` |
| `microjainslee.deployment.scan.enabled` | `scanEnabled` |

### Jakarta EE system-property mapping

| Property | Field |
|---|---|
| `microjainslee.config.eventRouterBufferSize` | `eventRouterBufferSize` |
| `microjainslee.config.preferVirtualThreads` | `preferVirtualThreads` |
| `microjainslee.config.sbbPoolMin` | `sbbPoolMin` |
| `microjainslee.config.sbbPoolMax` | `sbbPoolMax` |
| `microjainslee.config.sbbPerVirtualThread` | `sbbPerVirtualThread` |

Non-numeric or out-of-range values fall back to defaults and emit a
WARN log.

---

## 10. Concurrency contract

| Component | Thread model | Lock? |
|---|---|---|
| `EventRouter.routeEvent` | Any caller (RA, timer bridge, tests) | None — single Disruptor CAS publish |
| `Entity.submit(Runnable)` | Any caller | None — `LinkedBlockingQueue.offer` |
| `Sbb.onEvent(...)` | **One parked VT per SBB ID** | None — single-threaded by design |
| `TimerPort.setTimer` | The SBB's own VT | None — bridge reuses the same one |
| `Container.start` / `stop` | Main thread | `synchronized` on container instance |
| `MicroSleeExecutors.newVirtualThreadPerTaskExecutor` | Reflection (Java 8 compat) | None |

### Why no `synchronized` on the SBB callback path

Any `synchronized` block that a SBB callback enters would **pin** its
carrying virtual thread to its underlying OS thread, defeating the
"1 million concurrent SBBs" promise. The pool design forces SBB
authors to either:

- Stay single-threaded per ID (recommended — no synchronization needed), or
- Reach for **lock-free** primitives like `AtomicReference`,
  `ConcurrentHashMap`, `LongAdder` for any cross-SBB coordination.

If a SBB must use a `ReentrantLock`, the lock should be held only
across non-blocking calls and never call back into the SLEE container
while holding it.

Run with `-Djdk.tracePinnedThreads=full` to catch violations during
development.

---

## 11. Failure modes and recovery

| Failure | Detection | Recovery |
|---|---|---|
| SBB `onEvent` throws | EventLoop catches Throwable, logs at WARN, continues | None needed — next event still processed |
| Timer wheel back-pressure | Netty wheel auto-batches; jSS7 bridge logs only on `TimerRecord` allocation failure | Reduce `eventRouterBufferSize` or increase `sbbPoolMin` so VTs are pre-warmed |
| `Entity.submit` after `pool.shutdown()` | Throws `IllegalStateException` immediately | Catch and ignore; SBB should already be in cleanup state |
| Container double-start | `state == STARTED` short-circuits silently | None needed |
| Container double-stop | `state == STOPPED` short-circuits silently | None needed |
| WildFly Jakarta EE shutdown | `@PreDestroy` hook fires; `unbindAll()` catches `NamingException` and logs at WARN | Container is in `STOPPED` state, no orphan JNDI references |

---

## 12. Design decisions log

| Date | Decision | Rationale |
|---|---|---|
| 2026-06-25 | Use LMAX Disruptor, not `BlockingQueue` for event router | Lock-free MPMC at 100M+ events/sec; tested in production by LMAX Exchange |
| 2026-06-25 | Per-SBB parked virtual thread, not thread pool | Only way to honor JAIN SLEE §8 single-threaded ordering without locks; measured 14 OS threads for 100K VTs |
| 2026-06-25 | Java 8 source/target on all modules | Maximises downstream portability — embedding app can be Java 21+ for VTs without forcing the SLEE itself onto Java 21 |
| 2026-06-25 | Annotation processor via SPI, not Maven plugin | Lets any IDE / build tool pick up the processor automatically; no Maven-specific glue |
| 2026-06-25 | jSS7 `LocalTimerAdapter` for timers, not `ScheduledExecutorService` | jSS7 is the canonical SLEE timer backend; existing Infinispan HA config can drop in later for clustering |
| 2026-06-25 | `ConcurrentHashMap` for ACNF, not Infinispan | Single-JVM R&D scope; user can back the interface with a distributed map for HA later |
| 2026-06-26 | Dual license: GPLv3 + Commercial | Matches the MySQL / Qt / MariaDB model — open-source default, commercial escape hatch for proprietary users |
| 2026-06-26 | `MicroSleeExecutors` reflection shim for VT executor | Keep `jainslee-core` Java 8 bytecode-compatible while transparently using VTs on Java 21+ |

---

## 13. Namespace strategy (GAP-16)

micro-jainslee deliberately splits namespaces across modules so the core stays
portable and adapters own framework coupling:

| Layer | Package / namespace | Allowed dependencies |
|---|---|---|
| **jainslee-api** | `com.microjainslee.api.*` | JDK only — no `javax.*`, no `jakarta.*` |
| **jainslee-core** | `com.microjainslee.core.*` | `jainslee-api`, JDK, log4j2 |
| **adapter-quarkus** | `com.microjainslee.quarkus.*` | `jakarta.enterprise.*`, `org.jboss.logging`, Quarkus SPI |
| **adapter-jakartaee** | `com.microjainslee.jakartaee.*` | `jakarta.ejb.*`, `jakarta.annotation.*`, `javax.naming.*` |
| **jainslee-spring-boot-starter** | `com.microjainslee.spring.*` | Spring Boot 3 (`org.springframework.*`) |
| **RA / protocol layer** | vendor packages (e.g. `javax.sip.*`, jSS7) | Unchanged — protocol stacks keep their historical namespaces |

**Rules:**

1. Spec-facing port interfaces (`TracePort`, `UsagePort`, `AlarmPort`, `ProfileTablePort`, `NamingPort`) live in **jainslee-api** and never import Jakarta EE or Quarkus types.
2. Default in-memory implementations (`SimpleTracePort`, `SimpleUsagePort`, `SimpleAlarmPort`, `InMemoryProfileTablePort`, `InMemoryNamingPort`) live in **jainslee-core** and remain the fallback when no adapter is present.
3. Quarkus adapters bridge to JBoss Logging, Micrometer (optional), and CDI producers; OpenTelemetry correlation is achieved by adding `quarkus-opentelemetry` to the application — no OTel imports in the extension itself.
4. JAIN-SIP and legacy Mobicents container code under `container/` and `api/jar/` retain `javax.slee.*` / `javax.sip.*`; they are **not** part of the micro-jainslee reactor's public API.

---

## Further reading

- [`run-testcase-100k-sbb.md`](run-testcase-100k-sbb.md) — step-by-step
  guide to running the 10K / 50K / 100K stress test that validates
  `VirtualThreadSbbEntityPool` used by the Quarkus extension
- [`../README.md`](../README.md) — user-facing quickstart, Quarkus
  `application.properties`, and integration guides
- [`../optimizejainsleep2.md`](../optimizejainsleep2.md) — Phase 2
  (Javassist codegen) and Phase 3 (Spring Boot / Quarkus / Jakarta EE)
  roadmap

---

*Document version 1.0 — maintained alongside micro-jainslee 1.1.0.*
*For corrections, open an issue or email [nhanth87@gmail.com](mailto:nhanth87@gmail.com).*
