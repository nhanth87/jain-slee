# App Guide: example-quarkus-helloworld-web (HelloWorld Web on Quarkus)

> Detailed guide for the HelloWorld Web app — HTTP ingress via `ra-http-server` + JAIN SLEE SBB pipeline on Quarkus 3.
> See also: `docs/en/app-guide.md` (wiring pattern), `vendor-ras/ra-http-server/` (RA implementation).
> Last updated: 2026-07-19

---

## 1. What this example does

A minimal "Hello World" web app on Quarkus 3 + micro-jainslee. Quarkus is **CDI-only**
(no Quarkus HTTP listener). One port owns all traffic:

| Port | Owner | Role |
|---|---|---|
| **8080** | `ra-http-server` | JAIN SLEE ingress — `/`, `/health`, telemetry dashboard, `/api/telemetry/*` |

A request becomes an `HttpWebRequestEvent`, is routed to `HelloWorldSbb`, which either
serves health/monitor surfaces or returns a Hello World HTML page. Responses go back
through the injected `ra-http-server` command port.

Optional telemetry (`microjainslee.telemetry.enabled`) adds the dashboard at `/telemetry`, Prometheus scrape RA, and a batched log sink.

---

## 2. Directory layout

```
example/example-quarkus-helloworld-web/
├── pom.xml
├── build/
│   ├── build.xml                    ← Ant → Maven wrapper (Java 25)
│   ├── run.sh                       ← run packaged app
│   └── package-dist.sh              ← classic dist/<app>-jainslee/ layout
├── src/main/resources/
│   ├── application.properties
│   └── log4j2.xml
└── src/main/java/com/example/helloworld/quarkus/
    ├── bootstrap/
    │   └── HelloWorldBootstrap.java ← CDI: start container, telemetry, RA, SBB
    ├── profile/
    │   ├── SessionProfile.java        ← checkpoint / recovery row (example-local CMP)
    │   ├── AppUserProfile.java        ← thin app-user slice
    │   └── HelloWorldProfileManager.java ← ProfileFacility façade
    ├── telemetry/
    │   ├── AppTelemetry.java        ← Micrometer port + Prometheus RA + log sink
    │   └── TelemetryLogSink.java
    ├── http/
    │   ├── MonitorHandler.java      ← /telemetry GUI + /api/telemetry/*
    │   └── HttpReply.java
    └── sbbs/
        └── HelloWorldSbb.java       ← gateway SBB (/health → monitor → Hello World)
```

---

## 3. Dependencies and config

> File: `pom.xml`

Key dependencies:

- **Quarkus**: `quarkus-arc` only (CDI host — no REST / Undertow)
- **micro-jainslee**: `jainslee-core`, `jainslee-api`, `jainslee-apt`, **`adapter-quarkus`** (CDI producer for `MicroSleeContainer`)
- **RAs**: `ra-http-server`, `ra-prometheus-exporter`
- **Observability**: `jainslee-telemetry`, `jainslee-monitor`, Micrometer Prometheus registry

> Note: this example **does** use `adapter-quarkus`. `HelloWorldBootstrap` injects `MicroSleeContainer` from that extension.

`application.properties`:

```properties
# Quarkus hosts CDI only. All HTTP is ra-http-server.
http.ra.port=8080

# micro-jainslee core config (adapter-quarkus build-time mapping)
microjainslee.container.buffer-size=4096
microjainslee.container.prefer-virtual-threads=true
microjainslee.container.sbb-pool-min=16
microjainslee.container.sbb-pool-max=10000
quarkus.config.mapping.validate-unknown=false

# Optional telemetry (dashboard + /api/telemetry/* via ra-http-server)
microjainslee.telemetry.enabled=true
```

---

## 4. How the RA connects into jainslee

`HelloWorldBootstrap` wires everything in a fixed order.

> File: `src/main/java/.../bootstrap/HelloWorldBootstrap.java`

### Step 1: Observe `StartupEvent` (eager RA wiring)

`@ApplicationScoped` beans are lazy. If nothing injects the bootstrap, `@PostConstruct`
alone never runs and the HTTP RA stays unwired. Observe Quarkus `StartupEvent` instead:

```java
@Inject
MicroSleeContainer container;

void onStart(@Observes StartupEvent ev) {
    if (container.getState() != MicroSleeContainer.State.STARTED) {
        container.start();
    }
```

### Step 2: Optional telemetry

```java
TelemetryPort telemetry = null;
if (telemetryEnabled) {
    telemetry = appTelemetry.install(container);
}
MonitorHandler monitor = telemetry == null ? null : new MonitorHandler(telemetry);
```

### Step 3: Register the SBB (constructor collaborator)

```java
container.registerSbbType(HelloWorldSbb.class, () -> new HelloWorldSbb(monitor));
```

`MonitorHandler` is nullable when telemetry is off. The SBB receives it via constructor (collaborator pattern).

### Step 4: IES dispatcher + event map

```java
container.createIesDispatcher();
container.mapEventToSbb(HttpWebRequestEvent.class, "HelloWorldSbb");
```

One event type is mapped: every HTTP request from `ra-http-server` fires
`com.microjainslee.ra.httpserver.events.HttpWebRequestEvent`.

### Step 5: Wire the HTTP RA

```java
HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
ra.setPort(httpRaPort);
ra.setHost("0.0.0.0");

httpEndpoint = new HttpServerRaEndpoint(ra);
httpEndpoint.setPort(httpRaPort);
container.registerRa(httpEndpoint, httpEndpoint);
```

On `registerRa()`:

- `HttpServerRaEndpoint.activate(...)` builds a `ResourceAdaptorContext` from `RaBootstrapPort`
- then `delegate.raConfigure()` → `delegate.raActive()`
- `raActive()` starts the Vert.x HTTP server, binds `host:port`, and routes each request into the SLEE event pipeline

**Cleanup:**

```java
@PreDestroy
void shutdown() {
    appTelemetry.close();
    if (httpEndpoint != null) {
        httpEndpoint.deactivate();   // → raInactive() → close Vert.x server
    }
    if (container.getState() == MicroSleeContainer.State.STARTED) {
        container.stop();
    }
}
```

`HttpServerRaEndpoint` implements both `RaEndpointPort` and `RaCommandPort`.
`getRaName()` returns `"http-server-ra"`, matching `@InjectRa(name = "http-server-ra")` on `HelloWorldSbb`.

---

## 5. SBB — business logic

### 5.1 HelloWorldSbb

> File: `src/main/java/.../sbbs/HelloWorldSbb.java`

**Purpose:** single HTTP gateway SBB. Dispatches by path:

1. `MonitorHandler` — `/telemetry`, `/api/telemetry/*` (empty → fall through)
2. otherwise Hello World HTML

**Constructor collaborator:**

```java
public HelloWorldSbb(MonitorHandler monitor) {
    this.monitor = monitor;
}
```

**Event handling + response via command port:**

```java
@Override
public void onEvent(SleeEvent event, ActivityContextInterface aci) {
    if (!(event instanceof HttpWebRequestEvent req)) {
        return;
    }
    HttpReply reply = dispatch(req);
    http.sendCommand(new HttpServerCommand.HttpResponseExCommand(
            req.getSessionId(), reply.status(), reply.contentType(),
            reply.text(), reply.binary(), reply.headers()));
}
```

`@InjectRa(name = "http-server-ra")` injects the command port used to write the HTTP response.

### 5.2 Event type

This example uses the **RA-provided** event:

`com.microjainslee.ra.httpserver.events.HttpWebRequestEvent`

There is no app-defined duplicate event or command hierarchy.

---

## 6. Events & commands

| Event | Source | Handled by | Reply path |
|---|---|---|---|
| `HttpWebRequestEvent` (RA) | HTTP → Vert.x RA → EventRouter | `HelloWorldSbb` | `HttpResponseExCommand` via `@InjectRa` |

Telemetry surface (when enabled):

| Path | Handler |
|---|---|
| `/telemetry`, `/telemetry/*` | Dashboard GUI (`jainslee-monitor` classpath resources) |
| `/api/telemetry/snapshot` | JSON snapshot |
| `/api/telemetry/metrics` | Prometheus text scrape |
| `/api/telemetry/alarms` | Active alarms |
| `POST /api/telemetry/alarms/{id}/clear` | Clear alarm |
| `POST /api/telemetry/config` | Toggle auto-reconfig |

---

## 7. Call flow

```
┌─────────────┐     GET /|/health|/telemetry ┌──────────────────────────┐
│  Browser /   │ ───────────────────────────▶ │  Vert.x HTTP Server      │
│  curl        │   port 8080                  │  (HttpServerResource     │
└─────────────┘                               │   Adaptor.raActive)      │
                                               └────────────┬─────────────┘
                                                            │
                                                            ▼
                                               ┌──────────────────────────┐
                                               │ HttpServerResourceAdaptor │
                                               │  .route(req)             │
                                               │  ├─ read body async      │
                                               │  └─ fire HttpWebRequest  │
                                               │     Event → EventRouter  │
                                               └────────────┬─────────────┘
                                                            │
                                                            ▼
                                               ┌──────────────────────────┐
                                               │     EventRouter          │
                                               │  → HelloWorldSbb         │
                                               └────────────┬─────────────┘
                                                            │
                                                            ▼
                                               ┌──────────────────────────┐
                                               │    HelloWorldSbb         │
                                               │  ├─ /health JSON         │
                                               │  ├─ MonitorHandler?      │
                                               │  │   /telemetry,         │
                                               │  │   /api/telemetry/*    │
                                               │  └─ else Hello World HTML│
                                               │  → HttpResponseExCommand │
                                               └──────────────────────────┘
```

Quarkus does not open an HTTP port — CDI + live-reload only.

---

## 8. How to run

### Dev mode (hot reload)

Use this when editing SBBs / Java sources. Quarkus watches `src/` and reloads.

```bash
cd example/example-quarkus-helloworld-web
mvn quarkus:dev
# or: ant -f build/build.xml dev
```

`quarkus.build.skip=true` only skips **package** goals (`build` / `generate-code`),
not `quarkus:dev`.

**Live-reload:** `adapter-quarkus` registers `MicroJainsleeHotReplacementSetup`
on the runtime jar (`META-INF/services/...HotReplacementSetup`). In
`quarkus:dev` it polls `HotReplacementContext.doScan` every second so SBB edits
rebuild without a Quarkus HTTP listener. Look for
`[microjainslee] Dev live-reload scanner armed` at startup; after a save,
Quarkus restarts and `HelloWorldBootstrap` rewires the RA/SBB pool.

`./build/run.sh` / `ant run` start the **packaged** `quarkus-run.jar` — no live reload.

### Ant wrapper (recommended for packaging)

```bash
cd example/example-quarkus-helloworld-web
ant -f build/build.xml install-deps   # install reactor modules to ~/.m2
ant -f build/build.xml package        # Quarkus fast-jar → target/quarkus-app/
ant -f build/build.xml run            # ./build/run.sh
ant -f build/build.xml dist           # classic dist/<app>-jainslee/
```

After start (all on `ra-http-server` `:8080`):

- **HelloWorld**: `curl http://localhost:8080/`
- **Health**: `curl http://localhost:8080/health` → `{"status":"ok"}`
- **Telemetry dashboard**: `http://localhost:8080/telemetry`
- **Telemetry snapshot**: `curl http://localhost:8080/api/telemetry/snapshot`
- **Endpoint hit counts**: `curl http://localhost:8080/api/telemetry/endpoints`
  → `{"total":N,"endpoints":{"GET /":…,"GET /api/telemetry/endpoints":…}}`
  (also mirrored as Micrometer `http_endpoint_hits_total{method,path}` on `/api/telemetry/metrics`)

---

## 9. Tests

Unit tests cover the telemetry log sink (`TelemetryLogSinkTest`). Run:

```bash
mvn test
```

Suggested smoke pattern for the full pipeline (not required in-tree):

- Build a small `MicroSleeContainer` (small buffer, no virtual threads if desired)
- Set `http.ra.port=0` for an ephemeral bind
- Drive `HelloWorldBootstrap` / register RA + SBB as in production
- `curl` the bound port and assert Hello World / telemetry JSON

---

## 10. Profile (example-local domain models)

Domain CMP classes live **in this example app**, not in `jainslee-api`. They extend
`ProfileAbstractCmp` and use `ProfileAccessorInvoker` — same pattern as
`UssdSubscriberProfile` in `example-quarkus-ussdgw`.

| Class | Table | Purpose |
|---|---|---|
| `SessionProfile` | `SubscriberSession` | `checkpointJson`, `lastActivityId`, `profileKey` — crash recovery |
| `AppUserProfile` | `AppUser` | Thin user slice (`userId`, `displayName`, optional `msisdn`) |

`HelloWorldBootstrap` provisions tables then injects `HelloWorldProfileManager`
into `HelloWorldSbb`. On each `/` request the SBB keys a `SessionProfile` by HTTP
session id, bumps `checkpointJson.hits`, and on `sbbPassivate` refreshes
`lastActivityId`.

### Profile CMP vs SBB death (critical)

```
┌──────────────┐   write CMP    ┌─────────────────────────────────┐
│ SBB entity A │ ─────────────▶ │ ProfileFacility hot store       │
│ (heap)       │                │  SubscriberSession[sessionId]   │
└──────┬───────┘                │    checkpointJson.hits = 1      │
       │ sbbPassivate / kill    └─────────────────────────────────┘
       ▼                                        │
   A discarded                                  │ row still there
                                                ▼
┌──────────────┐   getOrCreate  ┌─────────────────────────────────┐
│ SBB entity B │ ◀───────────── │ same SessionProfile CMP row     │
│ (new heap)   │   sessionId    │    hits → 2                     │
└──────────────┘                └─────────────────────────────────┘
```

- **SBB CMP / heap** = ephemeral. When the entity dies, it is gone.
- **Profile CMP** = shared provisioned row. New SBB does **not** invent a fresh
  blank profile for an existing key — it **reloads** the CMP row from the
  facility (`getOrCreateSession` → `getProfile` hit).
- **Infinispan** (Phase 4) only makes that facility durable across JVM restart
  via write-behind; the recovery *contract* above already holds on the hot store.

Automated proof: `HelloWorldProfileRecoveryTest` — entity A hits → passivate →
entity B same session → `hits=2`.

**Where do tables live?**

| Layer | What | Lifetime |
|---|---|---|
| `createProfileTable(...)` | Logical table in `ProfileFacility` hot store (in-memory map today) | Process / until flush+clear |
| Profile CMP field maps | Rows (`SubscriberSession[sessionId]`, …) | Survive SBB passivate |
| **Infinispan** (`DurableProfileStore`, Phase 4) | Write-behind persistence of those field maps | Survive JVM restart |

App code never opens Infinispan caches directly — it only talks to
`ProfileFacility`. When Phase 4 wires `installDurableStore(InfinispanProfileStore)`,
the same `createProfileTable` / `getOrCreateSession` calls keep working; durability
is behind the facility.
