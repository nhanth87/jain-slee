# example-quarkus-ms

Quarkus 3 **CDI host** for the micro-jainslee microservice layer (`ms-api` / `ms-core` / `ms-ispn`), wired the same way as other Quarkus examples:

- `@Inject MicroSleeContainer` + `onStart(@Observes StartupEvent)` (adapter-quarkus owns create/start/stop)
- Real **SBBs** + **events** + **`ra-http-server`** ports — not Quarkus REST controllers calling services directly

```
HTTP ──► ra-http-server ──► HttpWebRequestEvent ──► MsGatewaySbb
                                                      │
                                         SleeServiceClient("signaling")
                                                      │
                                         Direct (single) or ISPN (cluster)

Local SLEE plane (SBB-to-SBB):
  MsServiceCallEvent ──► MsAppBridgeSbb ──► SleeServiceClient("signaling")
```

Same codebase, two deploy modes:

| Mode | What runs | How services call each other |
|------|-----------|------------------------------|
| **single** (default) | `signaling` + `app` in one JVM | `DirectServiceClient` (in-process) |
| **cluster** | each node activates only its services | Infinispan queue (`ms-ispn`) |

Business code only uses `SleeServiceClient` — it never chooses Direct vs ISPN.

```
@SleeService(name = "signaling")          ← leaf MS descriptor
@SleeService(name = "app", dependsOn = "signaling")
         │
         ▼
 MicrosleeMsSupport + SleeServiceClientFactory
    │
┌────┴────┐
Direct   IspnQueue  ← decided by deployment.yml + JAINSLEE_NODE_ID
```

---

## Prerequisites

- **Java 25**
- **Maven 3.9+**
- micro-jainslee `1.2.0-SNAPSHOT` installed in the local reactor:

```bash
export JAVA_HOME=/path/to/jdk-25
cd /path/to/jain-slee   # repo root
mvn -pl jainslee-ms/ms-api,jainslee-ms/ms-core,jainslee-ms/ms-ispn,jainslee-adapter/adapter-quarkus/runtime,vendor-ras/ra-http-server -am install -DskipTests
```

---

## Build

```bash
cd example/example-quarkus-ms
mvn package
# or from repo root:
mvn -Pexamples -pl example/example-quarkus-ms -am package
```

Runnable Quarkus app:

```text
target/quarkus-app/quarkus-run.jar
```

---

## 1) Single mode (recommended first)

### Start

```bash
./scripts/run-single.sh
# or:
mvn quarkus:dev
# or:
java -Dhttp.ra.port=8080 -jar target/quarkus-app/quarkus-run.jar
```

Listen: **http://127.0.0.1:8080** (`http.ra.port` — `ra-http-server`, not Quarkus HTTP)

Built-in RA probe: `GET /health` → `{"status":"ok"}`. Demo topology: `GET /api/health`.

### Smoke test

```bash
# Health / topology (MsGatewaySbb)
curl -s http://127.0.0.1:8080/api/health | jq .

# Local states + ISPN readiness + call counters
curl -s http://127.0.0.1:8080/api/ms/state | jq .

# Call signaling (Direct — viaLocal=true) via SBB chain
curl -s -X POST 'http://127.0.0.1:8080/api/demo/call-signaling?op=ping' \
  -H 'Content-Type: text/plain' -d '' | jq .
# → {"success":true,"payload":"pong","viaLocal":true,...}

curl -s -X POST 'http://127.0.0.1:8080/api/demo/call-signaling?op=echo' \
  -H 'Content-Type: text/plain' -d 'hello-ussd' | jq .
# → payload: "echo:hello-ussd"

# Fire-and-forget
curl -s -X POST 'http://127.0.0.1:8080/api/demo/notify-signaling?op=event' \
  -H 'Content-Type: text/plain' -d 'x' | jq .
```

Expected `/api/health` snippet:

```json
{
  "status": "UP",
  "mode": "SINGLE",
  "local": { "signaling": true, "app": true }
}
```

---

## 2) Cluster mode (two processes, ISPN queue)

Each process loads `deployment-cluster.yml` and activates **only** services assigned to its node id.

| Process | Node id | HTTP (`http.ra.port`) | Local services |
|---------|---------|----------------------|----------------|
| A | `node-signaling` | 8081 | `signaling` |
| B | `node-app` | 8082 | `app` |

`app` on node B calls `signaling` on node A through the Infinispan inbox/reply caches (needs `jainslee.ms.cluster-enabled=true`).

### Terminal A — signaling

```bash
./scripts/run-cluster-signaling.sh
```

### Terminal B — app (after A is up)

```bash
./scripts/run-cluster-app.sh
```

### Verify

```bash
# Signaling node: only signaling is local
curl -s http://127.0.0.1:8081/api/health | jq .
# → mode=CLUSTER, local.signaling=true, local.app=false

# App node: only app is local
curl -s http://127.0.0.1:8082/api/health | jq .
# → local.signaling=false, local.app=true

# From the app node: remote call (viaLocal=false)
curl -s -X POST 'http://127.0.0.1:8082/api/demo/call-signaling?op=sri-sm' \
  -H 'Content-Type: text/plain' -d 'imsi-001' | jq .
# → {"success":true,"payload":"sri-sm-ok:imsi-001","viaLocal":false,...}

curl -s http://127.0.0.1:8082/api/ms/state | jq .
```

If the remote call times out, check:

1. Both processes have `jainslee.ms.cluster-enabled=true`
2. Same `cluster-initial-hosts`
3. Signaling node started first and `/api/ms/state` shows `ispnStates.signaling=READY`

---

## HTTP API (via ra-http-server → MsGatewaySbb)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | RA built-in probe (`{"status":"ok"}`) |
| `GET` | `/api/health` | Mode, node id, which MS services are local |
| `GET` | `/api/ms/state` | Local orchestrator states, ISPN readiness, counters |
| `POST` | `/api/demo/call-signaling?op=` | Gateway SBB → `SleeServiceClient` (request/response) |
| `POST` | `/api/demo/notify-signaling?op=` | Gateway SBB → `SleeServiceClient.notify` |

Body for POST endpoints: raw `text/plain` (optional).

---

## Config knobs

| Property / env | Meaning |
|----------------|---------|
| `http.ra.port` | `ra-http-server` listen port (default 8080) |
| classpath `deployment.yml` | Default single topology |
| `-Djainslee.deployment.resource=deployment-cluster.yml` | Cluster topology resource |
| `-Djainslee.node-id=` / `JAINSLEE_NODE_ID` | This process’s node |
| `JAINSLEE_DEPLOYMENT_CONFIG=/path/file.yml` | Absolute topology file |
| `jainslee.ms.cluster-enabled` | Enable JGroups/Infinispan cluster |
| `jainslee.ms.cluster-initial-hosts` | JGroups discovery hosts |

---

## Layout

```
example/example-quarkus-ms/
├── README.md
├── pom.xml
├── scripts/
│   ├── run-single.sh
│   ├── run-cluster-signaling.sh
│   └── run-cluster-app.sh
└── src/main/java/com/example/ms/quarkus/
    ├── bootstrap/MsQuarkusBootstrap.java   ← CDI StartupEvent wiring
    ├── bootstrap/MsRuntimeHolder.java
    ├── sbbs/MsGatewaySbb.java              ← HTTP RA event → reply port
    ├── sbbs/MsAppBridgeSbb.java            ← MsServiceCallEvent → MS client
    ├── events/MsServiceCallEvent.java
    ├── http/HttpReply.java
    ├── services/{Signaling,App}Service.java ← @SleeService markers
    └── handlers/ServiceHandlers.java        ← MS invoke implementations
```

Boot sequence in `MsQuarkusBootstrap`:

1. `container.start()` (if needed)
2. `ClusterManager` + `MicrosleeMsSupport.start(...)`
3. `registerSbbType` → `createIesDispatcher` → `mapEventToSbb`
4. `registerRa(httpEndpoint, httpEndpoint)`

---

## Tests

```bash
cd example/example-quarkus-ms
mvn test
```

- `MsBootstrapLogicTest` — single-mode Direct + cluster-split ISPN (no HTTP)
- `MsHttpSleeSmokeTest` — real `ra-http-server` → `MsGatewaySbb` → `/api/health` and call-signaling
- `MsAppBridgeSbbTest` — local `MsServiceCallEvent` → `MsAppBridgeSbb` → signaling client

---

## Notes

- Quarkus is the **CDI host only** (`quarkus-arc`). There is **no** `quarkus-rest` — HTTP is exclusively `ra-http-server`.
- `ms-*` modules never import `jainslee-core` for transport; Quarkus wiring uses `adapter-quarkus` + `MicrosleeMsSupport`.
- This example **pins Infinispan 15.0.0.Final + protostream 5.0.1.Final** so Quarkus BOM cannot upgrade them to 16.x / 6.x (breaks `ClusterManager`).
- POST demo endpoints require `Content-Type: text/plain`.
- Pure-Java (non-Quarkus) sibling: `example/example-ms-two-service`.
- SLEE-shaped Quarkus siblings: `example-quarkus-helloworld-web`, `example-quarkus-ussdgw`.
- Design background: `docs/vi/microjainslee-microservice.md`.
