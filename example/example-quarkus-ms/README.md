# example-quarkus-ms

Quarkus 3 **CDI host** for the micro-jainslee microservice layer (`ms-api` / `ms-core` / `ms-ispn`):

- `@Inject MicroSleeContainer` + `onStart(@Observes StartupEvent)` (adapter-quarkus owns create/start/stop)
- Real **SBBs** + **events** + **`ra-http-server`** — not Quarkus REST controllers
- **n-n** handler bindings via `SleeServiceHandlerRegistry` (not one-handler-per-service)

```
HTTP :8081 ──► ra-http-server ──► HttpWebRequestEvent ──► MsGatewaySbb
                                                              │
                              ┌───────────────────────────────┼───────────────────────────────┐
                              ▼                               ▼                               ▼
                     client("http-ra")               client("http-aux")              client("http-sbb")
                     (local Direct)                  (local Direct)                  (ISPN → node-sbb)
```

### n-n diagram (handlers ↔ services ↔ nodes)

```
  Handlers                          Services                         Nodes
  ────────                          ────────                         ─────
  HttpRaService (self *) ─────────► http-ra  ─────────────────────► node-ra (:8081 ingress)
  HttpAuxService (self *) ────────► http-aux ─────────────────────► node-ra
  HttpSbbService (self *) ────────► http-sbb ─────────────────────► node-sbb (:8082 /health)
  MsSharedStatusProvider ──status──► http-ra + http-sbb + http-aux     (1 provider → N services)
  MsSharedDiagHandler ─────diag────► http-ra + http-sbb + http-aux     (1 instance → N services)
```

Same codebase, two deploy modes:

| Mode | What runs | How services call each other |
|------|-----------|------------------------------|
| **single** (default) | `http-ra` + `http-aux` + `http-sbb` in one JVM | `DirectServiceClient` (in-process) |
| **micro-services** | each process activates only its services | Infinispan queue (`ms-ispn`) |

`mode: micro-services` is **service placement** (which JVM runs which `@SleeService`).  
`jainslee.ms.cluster-enabled` is the **JGroups/Infinispan fabric** — different knob.  
Deprecated YAML alias: `mode: cluster` → same as `micro-services`.

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

Runnable Quarkus app: `target/quarkus-app/quarkus-run.jar`

---

## 1) Single mode (recommended first)

```bash
./scripts/run-single.sh
# or: mvn quarkus:dev
```

Listen: **http://127.0.0.1:8080** (`http.ra.port` — `ra-http-server`)

```bash
curl -s http://127.0.0.1:8080/api/health | jq .
curl -s -X POST 'http://127.0.0.1:8080/api/demo/call-ra?op=ping' \
  -H 'Content-Type: text/plain' -d '' | jq .
# → viaLocal=true
curl -s -X POST 'http://127.0.0.1:8080/api/demo/call-ra?op=status' \
  -H 'Content-Type: text/plain' -d '' | jq .
# → shared-status:http-ra  (n-n ServiceLoader provider)
```

---

## 2) Micro-services mode (two processes, ISPN queue)

**Ingress is on the RA node (:8081)** — not the SBB node.

| Process | Node id | HTTP (`http.ra.port`) | Local services | Role |
|---------|---------|----------------------|----------------|------|
| A | `node-ra` | **8081 (demo ingress)** | `http-ra`, `http-aux` | `ra-http-server` + `MsGatewaySbb` |
| B | `node-sbb` | 8082 | `http-sbb` | Business MS only; built-in `/health` (no gateway) |

### Terminal A — ingress / RA (start first)

```bash
./scripts/run-ms-ra.sh
```

### Terminal B — http-sbb (after A is up)

```bash
./scripts/run-ms-sbb.sh
```

### Verify — curl **8081** (primary)

```bash
curl -s http://127.0.0.1:8081/api/health | jq .
# → mode=MICROSERVICES, local.http-ra=true, local.http-aux=true, local.http-sbb=false

curl -s http://127.0.0.1:8081/api/ms/handlers | jq .
# → n-n bindings (self + status provider + diag)

# Local leaf on RA node
curl -s -X POST 'http://127.0.0.1:8081/api/demo/call-ra?op=ping' \
  -H 'Content-Type: text/plain' -d '' | jq .
# → {"success":true,"payload":"pong","service":"http-ra","viaLocal":true,...}

# Second local service (many services on one node)
curl -s -X POST 'http://127.0.0.1:8081/api/demo/call-aux?op=ping' \
  -H 'Content-Type: text/plain' -d '' | jq .
# → viaLocal=true, service=http-aux

# Cross-node: gateway on 8081 → ISPN → http-sbb on node-sbb
curl -s -X POST 'http://127.0.0.1:8081/api/demo/call-sbb?op=ping' \
  -H 'Content-Type: text/plain' -d '' | jq .
# → {"success":true,"payload":"http-sbb-handled:ping","service":"http-sbb","viaLocal":false,...}

# Shared n-n op (same provider on every service)
curl -s -X POST 'http://127.0.0.1:8081/api/demo/call-sbb?op=status' \
  -H 'Content-Type: text/plain' -d '' | jq .
# → payload=shared-status:http-sbb
```

### 8082 — not demo ingress

```bash
curl -s http://127.0.0.1:8082/health
# → RA built-in {"status":"ok"} — no /api/demo gateway here
```

If a remote call times out: both processes need `jainslee.ms.cluster-enabled=true`, same `cluster-initial-hosts`, and RA node started first with `ispnStates.http-ra=READY`.

---

## Wireshark / tcpdump

| Port | Traffic |
|------|---------|
| **8081** | Client → HTTP gateway (demo ingress) |
| 8082 | Optional `/health` on SBB node |
| 7800 | JGroups / Infinispan fabric |

```bash
wireshark -i lo -f "tcp port 8081 or tcp port 8082 or tcp port 7800"
sudo ./scripts/capture-lo.sh /tmp/quarkus-ms-lo.pcap
```

Suggested sequence: start capture → `run-ms-ra.sh` → `run-ms-sbb.sh` → `curl POST …:8081/api/demo/call-sbb?op=ping` → expect HTTP on **8081**, JGroups on **7800**, `"viaLocal":false`.

---

## HTTP API (via ra-http-server → MsGatewaySbb on ingress node)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | RA built-in probe (any node with HTTP RA) |
| `GET` | `/api/health` | Mode, node id, local services |
| `GET` | `/api/ms/state` | Orchestrator + ISPN + n-n counters |
| `GET` | `/api/ms/handlers` | n-n registry bindings |
| `POST` | `/api/demo/call-ra?op=` | Gateway → `http-ra` |
| `POST` | `/api/demo/call-aux?op=` | Gateway → `http-aux` |
| `POST` | `/api/demo/call-sbb?op=` | Gateway → `http-sbb` (remote in MS mode) |
| `POST` | `/api/demo/notify-ra\|aux\|sbb?op=` | Fire-and-forget notify |

Useful ops: `ping`, `echo`, `status` (shared provider), `diag` (shared programmatic).

Body for POST: raw `text/plain` (optional).

---

## Config knobs

| Property / env | Meaning |
|----------------|---------|
| `http.ra.port` | `ra-http-server` listen port |
| classpath `deployment.yml` | Default single topology |
| `-Djainslee.deployment.resource=deployment-microservices.yml` | Two-process topology |
| `-Djainslee.node-id=` / `JAINSLEE_NODE_ID` | This process’s node |
| `jainslee.ms.cluster-enabled` | Enable JGroups/Infinispan fabric |
| `jainslee.ms.cluster-initial-hosts` | JGroups discovery hosts |

---

## Layout

```
example/example-quarkus-ms/
├── README.md
├── pom.xml
├── scripts/
│   ├── run-single.sh
│   ├── run-ms-ra.sh      ← :8081 ingress
│   ├── run-ms-sbb.sh     ← :8082 health / http-sbb
│   └── capture-lo.sh
└── src/main/java/com/example/ms/quarkus/
    ├── bootstrap/MsQuarkusBootstrap.java
    ├── sbbs/MsGatewaySbb.java
    ├── sbbs/MsAppBridgeSbb.java
    └── services/
        ├── HttpRaService.java / HttpAuxService.java / HttpSbbService.java
        ├── MsSharedStatusProvider.java   ← ServiceLoader → many services
        └── MsSharedDiagHandler.java      ← programmatic → many services
```

---

## Tests

```bash
cd example/example-quarkus-ms
mvn test
```

- `MsBootstrapLogicTest` — single Direct + micro-services ISPN + n-n registry
- `MsHttpSleeSmokeTest` — RA → gateway → call-ra / shared status
- `MsAppBridgeSbbTest` — `MsServiceCallEvent` → bridge → http-ra

---

## Notes

- Quarkus is the **CDI host only** — no `quarkus-rest`.
- Example pins Infinispan **15.0.0.Final** + protostream **5.0.1.Final**.
- Sibling: `example/example-ms-two-service`. Design: `docs/vi/microjainslee-microservice.md`.
- This demo does **not** claim SS7 CONTINUE sticky failover — HTTP MS n-n only.
