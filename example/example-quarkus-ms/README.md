# example-quarkus-ms

Quarkus 3 **CDI host** for the micro-jainslee microservice layer (`ms-api` / `ms-core` / `ms-ispn`):

- `@Inject MicroSleeContainer` + `onStart(@Observes StartupEvent)` (adapter-quarkus owns create/start/stop)
- Real **SBBs** + **events** + **`ra-http-server`** — not Quarkus REST controllers

```
HTTP ──► ra-http-server ──► HttpWebRequestEvent ──► MsGatewaySbb
                                                      │
                                         SleeServiceClient("http-ra")
                                                      │
                                         Direct (single) or ISPN (micro-services)
```

Same codebase, two deploy modes:

| Mode | What runs | How services call each other |
|------|-----------|------------------------------|
| **single** (default) | `http-ra` + `http-sbb` in one JVM | `DirectServiceClient` (in-process) |
| **micro-services** | each process activates only its services | Infinispan queue (`ms-ispn`) |

`mode: micro-services` is **service placement** (which JVM runs which `@SleeService`).  
`jainslee.ms.cluster-enabled` is the **JGroups/Infinispan fabric** — different knob.  
Deprecated YAML alias: `mode: cluster` → same as `micro-services`.

```
@SleeService(name = "http-ra")
@SleeService(name = "http-sbb", dependsOn = "http-ra")
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
```

---

## 2) Micro-services mode (two processes, ISPN queue)

| Process | Node id | HTTP (`http.ra.port`) | Local services | Wiring |
|---------|---------|----------------------|----------------|--------|
| A | `node-ra` | 8081 | `http-ra` | HTTP RA only (`/health`) |
| B | `node-sbb` | 8082 | `http-sbb` | HTTP RA + gateway SBB → calls `http-ra` via ISPN |

### Terminal A — http-ra

```bash
./scripts/run-ms-ra.sh
```

### Terminal B — http-sbb (after A is up)

```bash
./scripts/run-ms-sbb.sh
```

### Verify

```bash
curl -s http://127.0.0.1:8081/health
# → RA built-in {"status":"ok"}

curl -s http://127.0.0.1:8082/api/health | jq .
# → mode=MICROSERVICES, local.http-sbb=true, local.http-ra=false

curl -s -X POST 'http://127.0.0.1:8082/api/demo/call-ra?op=ping' \
  -H 'Content-Type: text/plain' -d '' | jq .
# → {"success":true,"payload":"pong","viaLocal":false,...}
```

If the remote call times out: both processes need `jainslee.ms.cluster-enabled=true`, same `cluster-initial-hosts`, and RA node started first with `ispnStates.http-ra=READY`.

---

## Wireshark / tcpdump

Fixed ports for capture on loopback:

| Port | Traffic |
|------|---------|
| 8082 | Client → HTTP SBB gateway |
| 8081 | Optional health on RA node |
| 7800 | JGroups / Infinispan fabric |

```bash
# Live in Wireshark
wireshark -i lo -f "tcp port 8082 or tcp port 8081 or tcp port 7800"

# Or write a pcap
sudo ./scripts/capture-lo.sh /tmp/quarkus-ms-lo.pcap
```

Suggested sequence:

1. Start capture.
2. `./scripts/run-ms-ra.sh` then `./scripts/run-ms-sbb.sh`.
3. `curl` `POST http://127.0.0.1:8082/api/demo/call-ra?op=ping`.
4. Expect HTTP on **8082**, JGroups TCP on **7800**, response `"viaLocal":false`.

---

## HTTP API (via ra-http-server → MsGatewaySbb)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | RA built-in probe |
| `GET` | `/api/health` | Mode, node id, local services |
| `GET` | `/api/ms/state` | Orchestrator + ISPN + counters |
| `POST` | `/api/demo/call-ra?op=` | Gateway → `SleeServiceClient("http-ra")` |
| `POST` | `/api/demo/notify-ra?op=` | Fire-and-forget notify |
| `POST` | `/api/demo/call-signaling?op=` | Alias of `call-ra` |

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
│   ├── run-ms-ra.sh
│   ├── run-ms-sbb.sh
│   └── capture-lo.sh
└── src/main/java/com/example/ms/quarkus/
    ├── bootstrap/MsQuarkusBootstrap.java
    ├── sbbs/MsGatewaySbb.java
    ├── sbbs/MsAppBridgeSbb.java
    ├── services/{HttpRa,HttpSbb}Service.java
    └── handlers/ServiceHandlers.java
```

---

## Tests

```bash
cd example/example-quarkus-ms
mvn test
```

- `MsBootstrapLogicTest` — single Direct + micro-services split ISPN
- `MsHttpSleeSmokeTest` — RA → gateway → call-ra
- `MsAppBridgeSbbTest` — `MsServiceCallEvent` → bridge → http-ra

---

## Notes

- Quarkus is the **CDI host only** — no `quarkus-rest`.
- Example pins Infinispan **15.0.0.Final** + protostream **5.0.1.Final**.
- Sibling: `example/example-ms-two-service`. Design: `docs/vi/microjainslee-microservice.md`.
