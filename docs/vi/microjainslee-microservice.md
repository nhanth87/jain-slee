[USER] trong dự án micro-jainslee tôi muốn triển khai thành micro service (thêm micro service module module) vào là sẽ auto có rest api hoặc grpc để làm liên lạc giữa các microservice vd ss7 1 service, http 1 service... etc, xem co1 hợp lý và làm dc k?

---

[ASSISTANT] Đây là phân tích đầy đủ về ý tưởng của bạn. **Câu trả lời ngắn: Hợp lý và làm được — nhưng cần thiết kế cẩn thận để không phá vỡ triết lý core của micro-jainslee.**

---

## 🧠 Phân tích: "Microservice Module" trong micro-jainslee

### Bối cảnh hiện tại

micro-jainslee là một embeddable JAIN SLEE 1.1 runtime dạng single JAR, hướng đến 100k+ events/sec và sub-2s cold start, và Resource Adapters được cấu trúc theo Wrapper+Delegate pattern — wrapper implement SLEE lifecycle ports, delegate implement business protocol và command port.

Hiện tại micro-jainslee-2 đã có các modules: jainslee-tx, codegen, cluster, monitor, tck-harness, **grpc-simulator**, example-embedded-j25-ussdgw, example-cmr.

---

### ✅ Ý tưởng: `microservice-module` — Auto REST/gRPC bridge

Ý tưởng cốt lõi: **thêm một module mà khi một service deploy RA (SS7, HTTP, SIP...), nó tự động expose REST API hoặc gRPC endpoint để các service khác giao tiếp.**

#### Cách thiết kế hợp lý nhất (3 lớp):

```
┌─────────────────────────────────────────────────┐
│          microservice-bridge (module mới)        │
│                                                  │
│  ┌─────────────┐      ┌──────────────────────┐  │
│  │  REST Layer │      │    gRPC Layer         │  │
│  │ (Quarkus    │      │ (native Quarkus gRPC) │  │
│  │  REST/JAX-RS│      │  + .proto generated)  │  │
│  └──────┬──────┘      └──────────┬───────────┘  │
│         │                        │               │
│         └──────────┬─────────────┘               │
│                    ▼                              │
│        MicrosleeEventBridge (SPI)                 │
│   fireInboundEvent() / sendOutboundCommand()      │
│         │                                        │
└─────────┼────────────────────────────────────────┘
          │ dùng RaBootstrapPort / RaCommandPort
          ▼
    MicroSleeContainer (core)
         ├── SS7-RA  (service riêng)
         ├── HTTP-RA (service riêng)
         └── SIP-RA  (service riêng)
```

---

### 📦 Module layout đề xuất

```
micro-jainslee/
├── jainslee-api/
├── jainslee-core/
├── jainslee-apt/
├── adapters/
│   ├── adapter-quarkus/
│   └── adapter-spring/
├── ras/                          ← các RA hiện tại
│   ├── ra-ss7/
│   ├── ra-http/
│   └── ra-grpc/
│
└── microservice/                 ← MODULE MỚI
    ├── ms-api/                   ← SPI: MicrosleeServiceEndpoint interface
    ├── ms-rest/                  ← REST bridge (JAX-RS/Quarkus REST)
    ├── ms-grpc/                  ← gRPC bridge (native Quarkus gRPC)
    └── ms-discovery/             ← Service registry (Consul/Kubernetes/static)
```

---

### 🔧 Cơ chế "Auto expose" khi thêm module

Dùng cùng pattern bạn đã áp dụng với `CamelRaRouteContributor`:

```java
// SPI trong ms-api
public interface MicrosleeServiceEndpoint {
    String serviceName();        // "ss7", "http", "sip"...
    TransportType transport();   // REST | GRPC | BOTH
    List<EventDescriptor> exposedEvents();
    List<CommandDescriptor> acceptedCommands();
}

// Auto-discovery qua CDI (Quarkus) hoặc ServiceLoader (Spring)
// Khi RA được deploy → endpoint tự động register
```

```java
// Annotation đơn giản trên RA
@SleeService(name = "ss7", transport = TransportType.GRPC)
public class SS7ResourceAdaptor implements ResourceAdaptorSpi { ... }
```

Khi app khởi động → APT/CDI scan → auto-generate REST/gRPC routes.

---

### ⚖️ So sánh REST vs gRPC cho từng use case

**Khuyến nghị:** mỗi RA tự khai báo transport ưa thích; module `ms-grpc` dùng **native Quarkus gRPC** (bạn đã prefer từ trước để tránh Camel overhead).

---

### ⚠️ Các điểm cần cẩn thận

1. **Không để ms-module import vào** `jainslee-core` — phải đi qua Port (RaBootstrapPort, RaCommandPort). Tuyệt đối giữ core Pure Java.
2. **Event serialization**: SLEE events là in-JVM object → khi cross-service phải serialize (Protobuf cho gRPC, JSON cho REST). Cần định nghĩa contract rõ ở `ms-api`.
3. **ActivityContext cross-service**: nếu SS7 dialog ở service A mà SBB ở service B, cần `jainslee-cluster` (Hazelcast/Infinispan) để share AC state — bạn đã có module `cluster` rồi.
4. **Độ phức tạp tăng**: hiện tại RA chạy in-process → rất nhanh. Cross-service thêm latency mạng. Cân nhắc kỹ khi nào cần split thật sự.

---

### 🚀 Lộ trình triển khai

```
Phase 1: ms-api + ms-grpc (SS7 trước vì telecom-critical)
  └── SleeService annotation + APT codegen .proto từ EventDescriptor

Phase 2: ms-rest (HTTP RA → expose REST)
  └── Quarkus REST + auto route registration via CDI

Phase 3: ms-discovery
  └── Static config trước → Kubernetes/Consul sau

Phase 4: integrate với jainslee-cluster
  └── Cross-node ActivityContext via Hazelcast-backed AC store
```

---

### ✅ Kết luận

**Hoàn toàn hợp lý và làm được.** Đây thực chất là việc bổ sung một **microservice transport layer** phía trên RA boundary hiện có. Điểm mấu chốt là:

- `ms-*` modules chỉ được phép giao tiếp với core qua **RaBootstrapPort / RaCommandPort**
- Auto-expose dùng **CDI + annotation scanning** (bạn đã có pattern này trong `ra-camel`)
- gRPC cho telecom (SS7, DIAMETER), REST cho HTTP-facing services

Bạn muốn tôi thiết kế chi tiết `ms-api` SPI interface và Protobuf schema cho SS7 trước không?

---

[USER] ràng buộc giữa các micro service ntn? vd service A cần B, C để chạy, B cần C, D, D ko cần service gì hết

---

[ASSISTANT] Để trả lời chính xác, để tôi xem lại kiến trúc hiện tại của micro-jainslee trước.

---

[ASSISTANT] Đây là vấn đề **Service Dependency Management** — rất quan trọng khi thiết kế microservice cho micro-jainslee. Phân tích đầy đủ:

---

## 🔗 Ràng buộc giữa các Microservice trong micro-jainslee

### Bài toán cụ thể của bạn

```
A → cần B, C
B → cần C, D
D → không cần gì
C → không cần gì (leaf)

Thứ tự start hợp lệ: D → C → B → A
Thứ tự stop hợp lệ:  A → B → C/D
```

Đây là bài toán **DAG (Directed Acyclic Graph) dependency resolution** — hoàn toàn làm được và phù hợp với kiến trúc hiện tại của micro-jainslee.

---

## 🏗️ Thiết kế: `ms-dependency` trong module microservice

### 1. Annotation khai báo dependency (tại compile-time)

```java
// Trong ms-api module
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SleeService {
    String name();
    TransportType transport() default TransportType.GRPC;
    String[] dependsOn() default {};          // tên service cần
    String[] optionalDeps() default {};       // cần nhưng không bắt buộc
    int startPriority() default 100;          // thấp hơn = start trước
    long startupTimeoutMs() default 30_000;   // timeout chờ dep sẵn sàng
}

// Ví dụ khai báo
@SleeService(
    name = "service-a",
    transport = TransportType.GRPC,
    dependsOn = {"service-b", "service-c"}
)
public class ServiceA implements ResourceAdaptorSpi { ... }

@SleeService(
    name = "service-b",
    dependsOn = {"service-c", "service-d"}
)
public class ServiceB implements ResourceAdaptorSpi { ... }

@SleeService(name = "service-d")   // leaf, không cần ai
public class ServiceD implements ResourceAdaptorSpi { ... }
```

---

### 2. Dependency Graph Engine (runtime resolution)

```java
// Trong ms-core module
public class ServiceDependencyGraph {

    private final Map<String, ServiceNode> nodes = new ConcurrentHashMap<>();

    public void register(SleeServiceDescriptor descriptor) {
        nodes.put(descriptor.name(), new ServiceNode(descriptor));
    }

    // Topological sort (Kahn's algorithm) → start order
    public List<String> resolveStartOrder() {
        // 1. Build in-degree map
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();

        for (var node : nodes.values()) {
            inDegree.putIfAbsent(node.name(), 0);
            for (String dep : node.dependsOn()) {
                graph.computeIfAbsent(dep, k -> new ArrayList<>())
                     .add(node.name());
                inDegree.merge(node.name(), 1, Integer::sum);
            }
        }

        // 2. Kahn's BFS
        Queue<String> queue = new ArrayDeque<>();
        inDegree.forEach((name, deg) -> {
            if (deg == 0) queue.offer(name);  // leaf nodes first
        });

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            order.add(curr);
            for (String dependent : graph.getOrDefault(curr, List.of())) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    queue.offer(dependent);
                }
            }
        }

        // 3. Detect circular dependency
        if (order.size() != nodes.size()) {
            throw new CircularDependencyException(
                "Circular dependency detected in: " + findCycle()
            );
        }

        return order; // [D, C, B, A] ← start order
    }

    public List<String> resolveStopOrder() {
        var startOrder = resolveStartOrder();
        Collections.reverse(startOrder);
        return startOrder; // [A, B, C, D] ← stop order
    }
}
```

---

### 3. Startup Orchestrator — chờ dep sẵn sàng trước khi start

```java
public class MicrosleeServiceOrchestrator {

    private final ServiceDependencyGraph graph;
    private final ServiceRegistry registry;        // existing trong core
    private final Map<String, ServiceState> states = new ConcurrentHashMap<>();

    public void startAll() throws Exception {
        List<String> order = graph.resolveStartOrder();

        for (String serviceName : order) {
            waitForDependencies(serviceName);   // block cho đến khi dep READY
            doStart(serviceName);
            states.put(serviceName, ServiceState.READY);
            notifyDependents(serviceName);      // wake up những service đang chờ
        }
    }

    private void waitForDependencies(String serviceName) {
        ServiceNode node = graph.get(serviceName);
        for (String dep : node.dependsOn()) {
            long timeout = node.startupTimeoutMs();
            long deadline = System.currentTimeMillis() + timeout;

            while (states.get(dep) != ServiceState.READY) {
                if (System.currentTimeMillis() > deadline) {
                    throw new ServiceStartupTimeoutException(
                        serviceName + " timed out waiting for " + dep
                    );
                }
                Thread.sleep(50);  // Virtual thread → không block OS thread
            }
        }
    }

    // Stop theo reverse order
    public void stopAll() {
        List<String> order = graph.resolveStopOrder();
        for (String name : order) {
            doStop(name);
            states.put(name, ServiceState.STOPPED);
        }
    }
}
```

---

### 4. Tích hợp với RaBootstrapPort hiện có

```
┌──────────────────────────────────────────────────────────┐
│              MicrosleeServiceOrchestrator                │
│  resolveStartOrder() → [D, C, B, A]                     │
│         │                                                │
│         ▼  (theo thứ tự)                                │
│  ┌─────────────────────────────────┐                    │
│  │      RaBootstrapPort            │  ← existing port   │
│  │  raActive() / raInactive()      │                    │
│  └────────────┬────────────────────┘                    │
│               │                                          │
│    ┌──────────▼──────────┐                              │
│    │   MicroSleeContainer │  ← core, không đổi          │
│    │   ServiceRegistry    │                              │
│    └─────────────────────┘                              │
└──────────────────────────────────────────────────────────┘
```

RA hiện tại dùng Wrapper+Delegate pattern — wrapper implement SLEE lifecycle ports, Orchestrator chỉ gọi `raActive()` / `raInactive()` theo đúng thứ tự DAG mà không cần thay đổi core.

---

### 5. Health Check + Readiness Gate (cross-network)

Khi các service chạy trên **process riêng** (true microservice):

```java
// Thay vì chờ in-memory state, dùng health endpoint
public class RemoteServiceReadinessChecker {

    // Mỗi service expose: GET /q/health/ready  (Quarkus built-in)
    public boolean isReady(String serviceAddress, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(serviceAddress + "/q/health/ready"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                if (resp.statusCode() == 200) return true;
            } catch (Exception e) { /* retry */ }
            Thread.sleep(500);
        }
        return false;
    }
}
```

---

## 📊 Tổng hợp: 3 tầng ràng buộc

---

## ⚠️ Vấn đề cần xử lý

### Circular dependency

```
A → B → A  ← BỊ DETECT bởi Kahn's algorithm → throw CircularDependencyException
```

### Dependency crash sau khi start

```java
// Dùng ServiceState machine
STOPPED → STARTING → READY → DEGRADED → STOPPED

// Khi D crash → B chuyển sang DEGRADED → A nhận event → tự quyết định
public interface ServiceDependencyListener {
    void onDependencyDegraded(String depName, ServiceState newState);
    void onDependencyRestored(String depName);
}
```

### Optional dependency

```java
@SleeService(
    name = "service-a",
    dependsOn = {"service-b"},        // bắt buộc, chờ vô hạn (trong timeout)
    optionalDeps = {"service-cache"}  // không có cũng chạy được
)
```

---

## 🚀 Kết luận: Start order cho ví dụ của bạn

```
Dependency graph:
  A → {B, C}
  B → {C, D}
  C → {}
  D → {}

Resolved start order (Kahn):  D, C, B, A   ✅
Resolved stop  order:          A, B, C, D   ✅

APT sẽ generate:
  ServiceDependencyGraph.java   ← tại compile time
  ServiceStartOrderConstants.java
```

Bạn muốn tôi code chi tiết phần APT annotation processor để generate dependency graph tại compile-time, hay ưu tiên phần runtime health-check/circuit-breaker trước?

---

[USER] ý là tôi muốn viết 1 code duy nhất trong jainslee app, sau đó trong boot strap phân ra thành các service, và khi chạy nhiều node thì jainslee tự start that service only và vì nó biết chạy 1 mình nên sẽ gọi qua grpc/http rest

---

[ASSISTANT] À hiểu rồi! Đây là pattern **"Write Once, Deploy Anywhere"** — một codebase duy nhất, runtime tự quyết định chạy in-process hay split thành microservice. Rất thông minh và hoàn toàn khả thi!

---

## 🎯 Core Concept: Deployment Mode Detection

```
Cùng 1 code JAR duy nhất
         │
         ├── Single Node (local)
         │      └── A, B, C, D chạy IN-PROCESS
         │          gọi nhau qua method call trực tiếp
         │
         └── Multi Node (cluster)
                ├── Node 1: chạy Service A only
                │          gọi B, C qua gRPC
                ├── Node 2: chạy Service B, C
                └── Node 3: chạy Service D
```

---

## 🏗️ Thiết kế tổng thể

### Bước 1: Viết code — không quan tâm deployment

```java
// Developer chỉ viết thế này, KHÔNG biết sẽ deploy kiểu gì
@SleeService(
    name = "service-a",
    dependsOn = {"service-b", "service-c"}
)
public class ServiceA extends AbstractRa {

    @Inject
    SleeServiceClient<ServiceB> serviceB;  // không quan tâm local hay remote

    @Inject
    SleeServiceClient<ServiceC> serviceC;

    @Override
    public void processEvent(SleeEvent event, ActivityContext ac) {
        // Gọi B — runtime tự quyết in-process hay gRPC
        var result = serviceB.call(new RequestEvent("do-something", payload));
        serviceC.notify(new NotifyEvent("done", result));
    }
}
```

---

### Bước 2: Bootstrap — khai báo topology

```yaml
# deployment.yml — chỉ cần file này thay đổi khi deploy

mode: cluster   # single | cluster

services:
  service-a:
    node: node-1          # chạy trên node nào
    transport: grpc
    port: 9001

  service-b:
    node: node-2
    transport: grpc
    port: 9002

  service-c:
    node: node-2          # B và C cùng node → in-process call
    transport: grpc
    port: 9003

  service-d:
    node: node-3
    transport: grpc
    port: 9004

nodes:
  node-1: { host: 10.0.0.1 }
  node-2: { host: 10.0.0.2 }
  node-3: { host: 10.0.0.3 }
```

---

### Bước 3: Runtime tự phân tách

```java
public class MicrosleeBootstrap {

    public void boot(DeploymentConfig config) {
        String myNodeId = resolveMyNodeId();  // từ env: JAINSLEE_NODE_ID=node-1

        DeploymentMode mode = config.mode();

        if (mode == DeploymentMode.SINGLE) {
            // Start tất cả in-process, dùng DirectServiceClient
            startAllLocal(config);
            return;
        }

        // CLUSTER MODE
        for (ServiceConfig svc : config.services()) {
            if (svc.node().equals(myNodeId)) {
                // Service này chạy trên node hiện tại
                startLocal(svc);
            } else {
                // Service này ở node khác → tạo gRPC stub
                registerRemoteStub(svc);
            }
        }

        // Sau khi phân chia xong → start dependency theo DAG
        orchestrator.startInOrder(localServices, remoteStubs);
    }
}
```

---

## 🔌 `SleeServiceClient` — Transparent Proxy (trái tim của hệ thống)

```java
// Interface duy nhất — code business KHÔNG đổi dù local hay remote
public interface SleeServiceClient<T> {
    SleeResponse call(SleeRequest request);
    void notify(SleeEvent event);
    boolean isLocal();
}

// ---- Implementation 1: Local (same JVM) ----
public class DirectServiceClient<T> implements SleeServiceClient<T> {
    private final T serviceInstance;

    @Override
    public SleeResponse call(SleeRequest request) {
        // Gọi thẳng method — zero overhead
        return serviceInstance.handle(request);
    }

    @Override
    public boolean isLocal() { return true; }
}

// ---- Implementation 2: Remote (khác node) ----
public class GrpcServiceClient<T> implements SleeServiceClient<T> {
    private final ManagedChannel channel;
    private final SleeServiceGrpc.SleeServiceStub stub;

    @Override
    public SleeResponse call(SleeRequest request) {
        // Tự động serialize → gRPC → deserialize
        var proto = ProtoMapper.toProto(request);
        var respProto = stub.withDeadline(...).call(proto);
        return ProtoMapper.fromProto(respProto);
    }

    @Override
    public boolean isLocal() { return false; }
}

// ---- Implementation 3: Same node khác RA (in-process nhưng async) ----
public class EventBusServiceClient<T> implements SleeServiceClient<T> {
    // Dùng SLEE Event routing nội bộ — giữ AC context
    @Override
    public SleeResponse call(SleeRequest request) {
        return sleeContainer.fireAndWait(request.toSleeEvent(), ac);
    }
}
```

---

## 🏭 ClientFactory — Tự động chọn implementation

```java
@ApplicationScoped
public class SleeServiceClientFactory {

    private final DeploymentConfig config;
    private final String myNodeId;

    public <T> SleeServiceClient<T> create(String serviceName, Class<T> type) {
        ServiceConfig svc = config.getService(serviceName);

        if (config.isSingle()) {
            // Single mode → luôn direct
            return new DirectServiceClient<>(registry.get(type));
        }

        if (svc.node().equals(myNodeId)) {
            // Cùng node → direct hoặc EventBus tùy RA type
            return new DirectServiceClient<>(registry.get(type));
        }

        // Khác node → gRPC stub
        return new GrpcServiceClient<>(
            channelPool.get(svc.host(), svc.port())
        );
    }
}
```

---

## 📊 Lifecycle toàn cảnh

```
JAR khởi động (node-1)
│
├─ 1. Đọc deployment.yml
├─ 2. Resolve myNodeId = "node-1"  ← từ ENV var
├─ 3. Phân loại services:
│       LOCAL:  [service-a]         ← sẽ start thật
│       REMOTE: [service-b @ node-2, service-c @ node-2, service-d @ node-3]
│
├─ 4. DAG resolution:
│       service-a dependsOn [b, c]
│       b, c đã có remote stub → coi như READY
│       → start service-a
│
├─ 5. Inject vào ServiceA:
│       serviceB → GrpcServiceClient(10.0.0.2:9002)
│       serviceC → GrpcServiceClient(10.0.0.2:9003)
│
├─ 6. Expose gRPC server cho service-a tại :9001
│
└─ 7. Ready ✅

Khi ServiceA gọi serviceB.call(...):
  ├── isLocal()? NO
  └── serialize → gRPC → node-2:9002 → ServiceB.handle() → response
```

---

## 🔄 Single Mode — Không cần gRPC, zero overhead

```
JAR khởi động (single mode)
│
├─ 1. mode: single → skip network setup
├─ 2. Start tất cả: D → C → B → A (theo DAG)
├─ 3. Inject vào ServiceA:
│       serviceB → DirectServiceClient(serviceBInstance)
│       serviceC → DirectServiceClient(serviceCInstance)
│
└─ 4. serviceA.call(serviceB) == serviceB.handle() trực tiếp ✅
        (zero serialization, zero network)
```

---

## 🗂️ Module layout cuối cùng

```
micro-jainslee/
├── jainslee-api/
│   └── @SleeService, SleeServiceClient<T>, SleeRequest/Response
│
├── jainslee-core/
│   └── MicroSleeContainer (không đổi gì)
│
├── jainslee-ms/                         ← MODULE MỚI
│   ├── ms-api/
│   │   ├── SleeServiceClient.java       ← transparent proxy interface
│   │   ├── DeploymentConfig.java        ← đọc deployment.yml
│   │   └── SleeServiceDescriptor.java
│   │
│   ├── ms-core/
│   │   ├── MicrosleeBootstrap.java      ← phân tách local/remote
│   │   ├── ServiceDependencyGraph.java  ← DAG resolver
│   │   ├── SleeServiceClientFactory.java
│   │   └── DirectServiceClient.java
│   │
│   ├── ms-grpc/
│   │   ├── GrpcServiceClient.java       ← remote stub
│   │   ├── GrpcServiceServer.java       ← expose endpoint
│   │   └── slee_service.proto
│   │
│   └── ms-rest/
│       ├── RestServiceClient.java
│       └── RestServiceServer.java
│
└── jainslee-cluster/                    ← đã có, dùng cho AC sharing
```

---

## ✅ Tổng kết

**Nguyên tắc cốt lõi:**

- Developer chỉ viết business logic
- `@SleeService(dependsOn=...)` khai báo dependency
- `deployment.yml` quyết định topology
- `SleeServiceClientFactory` tự chọn Direct/gRPC/REST
- Core không đổi gì

Bạn muốn tôi bắt đầu với phần nào trước — `DeploymentConfig` parser, `SleeServiceClientFactory`, hay `.proto` schema cho gRPC?

---

[USER] Chỉ cần viết design cho kỹ để senior java code dc là ok

---

[ASSISTANT] Để tôi recall lại toàn bộ context kiến trúc hiện tại của micro-jainslee trước khi viết design.

---

[ASSISTANT] OK, đã có đủ context. Dưới đây là **Thiết kế kỹ thuật đầy đủ** — đủ chi tiết để senior Java developer implement trực tiếp.

---

# DESIGN DOCUMENT: micro-jainslee Microservice Module

**Version:** 1.0 | **Target:** Java 25 + Virtual Threads | **JAR:** single deployable

---

## 1. TỔNG QUAN KIẾN TRÚC

### 1.1 Triết lý cốt lõi

```
┌─────────────────────────────────────────────────────────────────┐
│                    SAME JAR, SAME CODE                          │
│                                                                 │
│  SINGLE mode:   [SS7-RA] ──direct call──► [HTTP-RA]            │
│                                                                 │
│  CLUSTER mode:  [SS7-RA] ──gRPC/REST────► [HTTP-RA @ node-2]   │
│                                                                 │
│  Developer KHÔNG biết và KHÔNG quan tâm đang ở mode nào        │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 Invariants tuyệt đối (KHÔNG được phá vỡ)

```
INV-1: ms-* modules KHÔNG ĐƯỢC import jainslee-core
        → chỉ giao tiếp qua RaBootstrapPort / RaCommandPort

INV-2: jainslee-core KHÔNG ĐƯỢC biết về ms-* modules
        → core là Pure Java, zero framework dependency

INV-3: SleeServiceClient<T> là boundary duy nhất
        → business code không gọi gRPC/REST trực tiếp

INV-4: @SleeService là nguồn khai báo dependency DUY NHẤT
        → không có XML, không có file config thứ hai
```

---

## 2. MODULE LAYOUT

```
micro-jainslee/
├── jainslee-api/              (hiện có) — SLEE types, ports
├── jainslee-core/             (hiện có) — MicroSleeContainer, EventRouter, etc.
├── jainslee-apt/              (hiện có) — annotation processor
├── jainslee-scheduler/        (hiện có)
├── jainslee-cluster/          (hiện có) — Hazelcast/Infinispan
│
├── adapters/
│   ├── adapter-quarkus/       (hiện có)
│   └── adapter-spring/        (hiện có)
│
├── ras/                       (hiện có)
│   ├── ra-ss7/
│   └── ra-http/
│
└── jainslee-ms/               ◄── MODULE MỚI (multi-module Maven)
    ├── pom.xml                    (parent, packaging=pom)
    ├── ms-api/                    ◄── SPI + contracts (NO framework dep)
    ├── ms-core/                   ◄── bootstrap + DAG + factory
    ├── ms-grpc/                   ◄── gRPC transport (Quarkus gRPC)
    ├── ms-rest/                   ◄── REST transport (JAX-RS)
    └── ms-discovery/              ◄── service registry (static/K8s/Consul)
```

### 2.1 Dependency matrix

```
ms-api        → jainslee-api only
ms-core       → ms-api, jainslee-api
ms-grpc       → ms-api, ms-core (NO jainslee-core)
ms-rest       → ms-api, ms-core (NO jainslee-core)
ms-discovery  → ms-api only

adapter-quarkus → ms-core, ms-grpc, ms-rest, ms-discovery (wiring layer)
adapter-spring  → ms-core, ms-grpc, ms-rest, ms-discovery (wiring layer)
```

---

## 3. ms-api — SPI & Contracts

### 3.1 `@SleeService` annotation

```java
package com.microjainslee.ms.api.annotation;

/**
 * Khai báo một RA là một deployable microservice.
 * Đây là NGUỒN KHAI BÁO DUY NHẤT cho:
 *   - tên service
 *   - transport protocol
 *   - dependency graph
 *
 * Được xử lý bởi:
 *   - jainslee-apt tại compile-time  → sinh SleeServiceDescriptor
 *   - MicrosleeBootstrap tại runtime → DAG resolution + client wiring
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface SleeService {

    /** Tên service duy nhất trong cluster. Dùng làm service discovery key. */
    String name();

    /** Transport mà service này EXPOSE cho người khác gọi vào. */
    TransportType transport() default TransportType.GRPC;

    /**
     * Danh sách tên service mà service này BẮT BUỘC cần.
     * Runtime sẽ chờ (trong startupTimeoutMs) cho đến khi tất cả READY.
     * Circular dependency → CircularDependencyException tại startup.
     */
    String[] dependsOn() default {};

    /**
     * Dependency tùy chọn.
     * Runtime KHÔNG chờ — nếu không có vẫn start bình thường.
     * SleeServiceClient sẽ trả về NoOpServiceClient khi absent.
     */
    String[] optionalDeps() default {};

    /**
     * Thứ tự start khi có nhiều leaf node (in-degree = 0).
     * Số nhỏ hơn → start trước.
     * Default 100. Dùng khi cần kiểm soát thứ tự trong cùng level DAG.
     */
    int startPriority() default 100;

    /**
     * Thời gian tối đa (ms) chờ dependency READY trước khi fail.
     * Áp dụng per-dependency. Tổng worst-case = depth × timeout.
     */
    long startupTimeoutMs() default 30_000L;
}
```

### 3.2 `TransportType` enum

```java
package com.microjainslee.ms.api.annotation;

public enum TransportType {
    /**
     * gRPC (Protobuf).
     * Dùng cho: SS7, DIAMETER, SIP, telecom signaling.
     * Ưu tiên cho service-to-service internal.
     */
    GRPC,

    /**
     * REST/HTTP (JSON).
     * Dùng cho: HTTP-RA, external-facing, browser clients.
     */
    REST,

    /**
     * Expose cả hai. Runtime tạo cả 2 server endpoints.
     * SleeServiceClient phía caller dùng GRPC theo mặc định.
     */
    BOTH,

    /**
     * In-process only. Service này KHÔNG được gọi từ node khác.
     * Dùng cho internal helper RA không cần expose ra ngoài.
     */
    LOCAL_ONLY
}
```

### 3.3 `SleeServiceClient<T>` — Transparent proxy interface

```java
package com.microjainslee.ms.api;

/**
 * Boundary duy nhất để một service gọi service khác.
 * Implementation được inject bởi SleeServiceClientFactory.
 *
 * T = interface của target service (được generate bởi APT).
 *
 * Caller không biết (và không được biết) phía sau là:
 *   - DirectServiceClient    (same JVM)
 *   - GrpcServiceClient      (remote node, gRPC)
 *   - RestServiceClient      (remote node, REST)
 *   - NoOpServiceClient      (optional dep, absent)
 */
public interface SleeServiceClient<T> {

    /**
     * Synchronous request-response.
     * Virtual thread sẽ park nếu remote — không block OS thread.
     *
     * @throws ServiceUnavailableException nếu target không READY
     * @throws ServiceCallTimeoutException nếu vượt quá callTimeoutMs
     */
    SleeResponse call(SleeRequest request);

    /**
     * Fire-and-forget. Không chờ response.
     * Với GRPC: dùng unary call bỏ qua response.
     * Với Direct: gọi async trên virtual thread mới.
     */
    void notify(SleeEvent event);

    /** True nếu target service chạy cùng JVM. */
    boolean isLocal();

    /** Tên service target. Dùng cho logging/tracing. */
    String targetServiceName();

    /** State hiện tại của service target (READY/DEGRADED/STOPPED). */
    ServiceState targetState();
}
```

### 3.4 `SleeRequest` / `SleeResponse` / `SleeEvent`

```java
package com.microjainslee.ms.api;

/**
 * Envelope cho synchronous call.
 * Header mang correlation-id, trace-id, caller-node.
 * Body là byte[] — serialization do transport layer quyết định
 * (Protobuf cho gRPC, JSON cho REST).
 */
public final class SleeRequest {
    private final String correlationId;   // UUID v7
    private final String traceId;         // OpenTelemetry trace
    private final String operation;       // tên operation, vd "sendSms"
    private final String callerNode;      // node-id của caller
    private final byte[] payload;         // serialized body
    private final Map<String, String> headers;

    // static factory
    public static SleeRequest of(String operation, byte[] payload) { ... }
    public static Builder builder() { ... }
}

public final class SleeResponse {
    private final String correlationId;
    private final StatusCode status;      // OK, ERROR, TIMEOUT, NOT_FOUND
    private final byte[] payload;
    private final String errorMessage;    // null nếu OK

    public boolean isOk() { return status == StatusCode.OK; }
}

/**
 * Fire-and-forget envelope.
 * Không có correlationId vì không có response.
 */
public final class SleeEvent {
    private final String eventType;       // vd "SmsDelivered"
    private final String sourceService;
    private final byte[] payload;
    private final Map<String, String> headers;
}
```

### 3.5 `SleeServiceDescriptor` — Runtime metadata

```java
package com.microjainslee.ms.api;

/**
 * Được generate bởi jainslee-apt tại compile-time từ @SleeService.
 * Runtime đọc qua ServiceLoader hoặc CDI BeanManager.
 *
 * APT sinh ra: META-INF/services/com.microjainslee.ms.api.SleeServiceDescriptor
 * với một entry per @SleeService class.
 */
public interface SleeServiceDescriptor {
    String name();
    TransportType transport();
    String[] dependsOn();
    String[] optionalDeps();
    int startPriority();
    long startupTimeoutMs();
    Class<?> raClass();   // class được annotate @SleeService
}

// APT generate implementation ví dụ:
// (file: target/generated-sources/apt/com/example/SS7RaSleeServiceDescriptor.java)
public final class SS7RaSleeServiceDescriptor implements SleeServiceDescriptor {
    public String name()             { return "ss7"; }
    public TransportType transport() { return TransportType.GRPC; }
    public String[] dependsOn()      { return new String[]{}; }
    public String[] optionalDeps()   { return new String[]{}; }
    public int startPriority()       { return 10; }
    public long startupTimeoutMs()   { return 30_000L; }
    public Class<?> raClass()        { return SS7ResourceAdaptor.class; }
}
```

---

## 4. ms-core — Bootstrap, DAG, Factory

### 4.1 `DeploymentConfig` — Đọc deployment.yml

```java
package com.microjainslee.ms.core.config;

/**
 * Immutable config object.
 * Load từ: deployment.yml (classpath) hoặc env JAINSLEE_DEPLOYMENT_CONFIG.
 * Validated tại startup — fail-fast nếu thiếu node assignment.
 */
public final class DeploymentConfig {

    public enum Mode { SINGLE, CLUSTER }

    private final Mode mode;
    private final String myNodeId;         // từ env: JAINSLEE_NODE_ID
    private final Map<String, NodeConfig> nodes;
    private final Map<String, ServiceAssignment> services;

    /** True nếu serviceName được assign cho myNodeId. */
    public boolean isLocal(String serviceName) {
        if (mode == Mode.SINGLE) return true;
        ServiceAssignment sa = services.get(serviceName);
        return sa != null && myNodeId.equals(sa.nodeId());
    }

    public NodeConfig getNodeConfig(String serviceName) {
        return nodes.get(services.get(serviceName).nodeId());
    }

    public static DeploymentConfig load() {
        // 1. Tìm file: classpath:deployment.yml
        // 2. Parse YAML (Jackson YAML hoặc SnakeYAML)
        // 3. Validate: tất cả service phải có node assignment nếu CLUSTER
        // 4. Return immutable instance
    }
}

public record NodeConfig(String host, int basePort) {}

public record ServiceAssignment(
    String nodeId,
    TransportType preferredTransport,
    int port    // override port, 0 = auto từ basePort + index
) {}
```

**deployment.yml:**

```yaml
mode: cluster                # single | cluster
my-node-env-var: JAINSLEE_NODE_ID   # env var chứa node id

nodes:
  node-1: { host: "10.0.0.1", base-port: 9000 }
  node-2: { host: "10.0.0.2", base-port: 9000 }
  node-3: { host: "10.0.0.3", base-port: 9000 }

services:
  ss7:    { node: node-1, port: 9001 }
  http:   { node: node-2, port: 9002 }
  sip:    { node-2, port: 9003 }
  smpp:   { node: node-3, port: 9004 }
```

### 4.2 `ServiceDependencyGraph` — DAG Engine

```java
package com.microjainslee.ms.core.dag;

/**
 * Builds và resolves dependency graph từ SleeServiceDescriptor list.
 * Thread-safe sau khi build() — read-only sau đó.
 *
 * Sử dụng Kahn's Algorithm (BFS topological sort).
 * Time complexity: O(V + E) với V = services, E = dependencies.
 */
public final class ServiceDependencyGraph {

    // Node trong graph
    private record ServiceNode(
        SleeServiceDescriptor descriptor,
        Set<String> dependsOn,     // required deps
        Set<String> optionalDeps
    ) {}

    private final Map<String, ServiceNode> nodes;

    private ServiceDependencyGraph(Map<String, ServiceNode> nodes) {
        this.nodes = Map.copyOf(nodes);
    }

    /** Factory method — validate và build graph. */
    public static ServiceDependencyGraph build(
            List<SleeServiceDescriptor> descriptors) {

        Map<String, ServiceNode> nodes = new LinkedHashMap<>();

        // 1. Register tất cả nodes
        for (SleeServiceDescriptor d : descriptors) {
            if (nodes.containsKey(d.name())) {
                throw new DuplicateServiceNameException(
                    "Duplicate @SleeService name: " + d.name());
            }
            nodes.put(d.name(), new ServiceNode(
                d,
                Set.of(d.dependsOn()),
                Set.of(d.optionalDeps())
            ));
        }

        // 2. Validate: tất cả dependsOn phải tồn tại
        for (ServiceNode node : nodes.values()) {
            for (String dep : node.dependsOn()) {
                if (!nodes.containsKey(dep)) {
                    throw new UnknownDependencyException(
                        node.descriptor().name() + " depends on unknown service: " + dep);
                }
            }
            // optionalDeps: log WARN nếu không tồn tại, không throw
        }

        // 3. Detect cycle TRƯỚC KHI return
        ServiceDependencyGraph graph = new ServiceDependencyGraph(nodes);
        graph.detectCycle();  // throws CircularDependencyException nếu có cycle

        return graph;
    }

    /**
     * Topological sort (Kahn's BFS).
     * Trong cùng level (in-degree = 0), sort theo startPriority ASC.
     *
     * @return ordered list, index 0 = start first (leaf dependencies)
     */
    public List<String> resolveStartOrder() {
        // in-degree map
        Map<String, Integer> inDegree = new HashMap<>();
        // adjacency: dep → list of services that depend on dep
        Map<String, List<String>> adj = new HashMap<>();

        for (ServiceNode node : nodes.values()) {
            inDegree.putIfAbsent(node.descriptor().name(), 0);
            for (String dep : node.dependsOn()) {
                adj.computeIfAbsent(dep, k -> new ArrayList<>())
                   .add(node.descriptor().name());
                inDegree.merge(node.descriptor().name(), 1, Integer::sum);
            }
        }

        // Priority queue: ưu tiên startPriority nhỏ hơn trước
        PriorityQueue<String> queue = new PriorityQueue<>(
            Comparator.comparingInt(name ->
                nodes.get(name).descriptor().startPriority())
        );

        inDegree.forEach((name, deg) -> {
            if (deg == 0) queue.offer(name);
        });

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            order.add(curr);
            for (String dependent : adj.getOrDefault(curr, List.of())) {
                int newDeg = inDegree.merge(dependent, -1, Integer::sum);
                if (newDeg == 0) queue.offer(dependent);
            }
        }

        if (order.size() != nodes.size()) {
            // Không nên xảy ra vì đã detectCycle() ở build()
            throw new CircularDependencyException(findCycleDescription());
        }

        return Collections.unmodifiableList(order);
    }

    public List<String> resolveStopOrder() {
        List<String> start = new ArrayList<>(resolveStartOrder());
        Collections.reverse(start);
        return Collections.unmodifiableList(start);
    }

    public Set<String> getDependenciesOf(String serviceName) {
        return nodes.get(serviceName).dependsOn();
    }

    public boolean isOptional(String serviceName, String depName) {
        return nodes.get(serviceName).optionalDeps().contains(depName);
    }

    /**
     * DFS cycle detection.
     * @throws CircularDependencyException với mô tả path của cycle
     */
    private void detectCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String name : nodes.keySet()) {
            if (!visited.contains(name)) {
                dfsDetect(name, visited, inStack, new ArrayDeque<>());
            }
        }
    }

    private void dfsDetect(String name, Set<String> visited,
                           Set<String> inStack, Deque<String> path) {
        visited.add(name);
        inStack.add(name);
        path.push(name);

        for (String dep : nodes.get(name).dependsOn()) {
            if (!visited.contains(dep)) {
                dfsDetect(dep, visited, inStack, path);
            } else if (inStack.contains(dep)) {
                // Reconstruct cycle path cho error message
                List<String> cycle = new ArrayList<>(path);
                Collections.reverse(cycle);
                int idx = cycle.indexOf(dep);
                throw new CircularDependencyException(
                    "Circular dependency: " + String.join(" → ",
                        cycle.subList(idx, cycle.size())) + " → " + dep
                );
            }
        }

        path.pop();
        inStack.remove(name);
    }

    private String findCycleDescription() { return "cycle detected"; }
}
```

### 4.3 `ServiceState` — State machine

```java
package com.microjainslee.ms.core;

/**
 * Lifecycle state của một deployed service.
 *
 * Transitions hợp lệ:
 *   REGISTERED → WAITING_DEPS → STARTING → READY
 *   READY      → DEGRADED
 *   DEGRADED   → READY (khi dep recover)
 *   READY      → STOPPING → STOPPED
 *   STARTING   → FAILED (timeout hoặc exception)
 *   FAILED     → STOPPING → STOPPED
 */
public enum ServiceState {
    REGISTERED,       // đã đăng ký, chưa check deps
    WAITING_DEPS,     // chờ dependency READY
    STARTING,         // đang gọi raActive()
    READY,            // sẵn sàng nhận request
    DEGRADED,         // một dep bị crash, vẫn chạy nhưng limited
    STOPPING,         // đang gọi raInactive()
    STOPPED,          // đã dừng hoàn toàn
    FAILED            // không start được
}
```

### 4.4 `MicrosleeServiceOrchestrator` — Start/Stop theo DAG

```java
package com.microjainslee.ms.core;

/**
 * Điều phối start/stop theo thứ tự DAG.
 * Giao tiếp với jainslee-core ONLY qua RaBootstrapPort.
 *
 * Threading: mỗi service start trên một virtual thread riêng.
 * waitForDependencies() park virtual thread, không block OS thread.
 */
public final class MicrosleeServiceOrchestrator {

    private final ServiceDependencyGraph graph;
    private final DeploymentConfig config;
    private final SleeServiceClientFactory clientFactory;
    private final RaBootstrapPort raBootstrapPort; // ← ONLY link to core
    private final Map<String, AtomicReference<ServiceState>> states;
    private final Map<String, CountDownLatch> readyLatches;

    public MicrosleeServiceOrchestrator(
            ServiceDependencyGraph graph,
            DeploymentConfig config,
            SleeServiceClientFactory clientFactory,
            RaBootstrapPort raBootstrapPort) {
        this.graph = graph;
        this.config = config;
        this.clientFactory = clientFactory;
        this.raBootstrapPort = raBootstrapPort;

        // Initialize state maps
        this.states = new ConcurrentHashMap<>();
        this.readyLatches = new ConcurrentHashMap<>();

        graph.resolveStartOrder().forEach(name -> {
            states.put(name, new AtomicReference<>(ServiceState.REGISTERED));
            readyLatches.put(name, new CountDownLatch(1));
        });
    }

    /**
     * Start tất cả local services theo DAG order.
     * Remote services được coi là READY khi health check pass.
     *
     * Mỗi service chạy trên virtual thread riêng.
     * Method này block cho đến khi ALL services READY hoặc có FAILED.
     */
    public void startAll() throws InterruptedException {
        List<String> startOrder = graph.resolveStartOrder();

        // Pre-mark remote services là READY (sẽ check health sau)
        for (String name : startOrder) {
            if (!config.isLocal(name)) {
                transition(name, ServiceState.READY);
            }
        }

        // Start local services theo thứ tự
        // Dùng StructuredTaskScope để track failures
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            for (String name : startOrder) {
                if (config.isLocal(name)) {
                    final String svcName = name;
                    scope.fork(() -> {
                        startService(svcName);
                        return null;
                    });
                }
            }
            scope.join().throwIfFailed(e ->
                new ServiceStartupException("Startup failed", e));
        }
    }

    private void startService(String name) throws Exception {
        transition(name, ServiceState.WAITING_DEPS);

        // Chờ tất cả required deps
        for (String dep : graph.getDependenciesOf(name)) {
            waitForReady(dep, getDescriptor(name).startupTimeoutMs());
        }

        transition(name, ServiceState.STARTING);

        try {
            // Inject clients vào RA TRƯỚC khi gọi raActive()
            injectClients(name);

            // Gọi qua RaBootstrapPort — KHÔNG gọi trực tiếp vào RA
            raBootstrapPort.activateRa(name);

            // Nếu CLUSTER và là local → start gRPC/REST server
            if (config.mode() == DeploymentConfig.Mode.CLUSTER) {
                clientFactory.startServerFor(name);
            }

            transition(name, ServiceState.READY);
            readyLatches.get(name).countDown(); // unblock waiters

        } catch (Exception e) {
            transition(name, ServiceState.FAILED);
            throw new ServiceStartupException("Failed to start: " + name, e);
        }
    }

    /**
     * Block (park virtual thread) cho đến khi dep READY.
     * Không block OS thread nhờ virtual thread + CountDownLatch.
     */
    private void waitForReady(String depName, long timeoutMs)
            throws InterruptedException {

        CountDownLatch latch = readyLatches.get(depName);
        if (latch == null) {
            // Remote dep chưa có latch → dùng health check
            waitForRemoteReady(depName, timeoutMs);
            return;
        }

        boolean ready = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!ready) {
            throw new ServiceStartupTimeoutException(
                "Timed out waiting for dependency: " + depName +
                " (timeout=" + timeoutMs + "ms)");
        }
    }

    private void waitForRemoteReady(String depName, long timeoutMs)
            throws InterruptedException {
        // Delegate sang RemoteServiceHealthChecker
        clientFactory.getHealthChecker(depName)
                     .waitUntilReady(timeoutMs);
    }

    /**
     * Stop theo reverse DAG order.
     * A stop trước B, C — sau đó B stop — cuối cùng D.
     */
    public void stopAll() {
        List<String> stopOrder = graph.resolveStopOrder();
        for (String name : stopOrder) {
            if (config.isLocal(name)) {
                stopService(name);
            }
        }
    }

    private void stopService(String name) {
        transition(name, ServiceState.STOPPING);
        try {
            clientFactory.stopServerFor(name);
            raBootstrapPort.deactivateRa(name);
            transition(name, ServiceState.STOPPED);
        } catch (Exception e) {
            log.error("Error stopping service: " + name, e);
            transition(name, ServiceState.STOPPED); // force stop
        }
    }

    private void transition(String name, ServiceState newState) {
        states.get(name).set(newState);
        publishStateChange(name, newState); // fire SLEE event nếu cần
    }
}
```

### 4.5 `SleeServiceClientFactory` — Chọn implementation

```java
package com.microjainslee.ms.core;

/**
 * Factory tạo đúng implementation của SleeServiceClient<T>.
 *
 * Logic quyết định:
 *   SINGLE mode              → DirectServiceClient
 *   CLUSTER + same node      → DirectServiceClient
 *   CLUSTER + different node → GrpcServiceClient hoặc RestServiceClient
 *   optional dep + absent    → NoOpServiceClient
 *
 * Thread-safe. Client instances được cache sau khi tạo.
 */
public final class SleeServiceClientFactory {

    private final DeploymentConfig config;
    private final ServiceRegistry serviceRegistry;  // from jainslee-core via port
    private final GrpcChannelPool grpcChannelPool;
    private final Map<String, SleeServiceClient<?>> clientCache;
    private final Map<String, Object> serverInstances;  // gRPC/REST servers

    @SuppressWarnings("unchecked")
    public <T> SleeServiceClient<T> create(
            String targetServiceName,
            Class<T> serviceInterface) {

        return (SleeServiceClient<T>) clientCache.computeIfAbsent(
            targetServiceName,
            name -> buildClient(name, serviceInterface)
        );
    }

    private <T> SleeServiceClient<T> buildClient(
            String name, Class<T> serviceInterface) {

        // Case 1: optional dep không tồn tại trong deployment
        if (!config.hasService(name)) {
            log.warn("Optional dependency '{}' not in deployment config", name);
            return new NoOpServiceClient<>(name);
        }

        // Case 2: single mode hoặc cùng node
        if (config.isLocal(name)) {
            T instance = serviceRegistry.getByInterface(serviceInterface);
            return new DirectServiceClient<>(name, instance);
        }

        // Case 3: remote node — chọn transport
        NodeConfig nodeConfig = config.getNodeConfig(name);
        TransportType transport = config.getService(name).preferredTransport();

        return switch (transport) {
            case GRPC, BOTH -> new GrpcServiceClient<>(
                name,
                grpcChannelPool.getOrCreate(nodeConfig.host(), nodeConfig.port()),
                serviceInterface
            );
            case REST -> new RestServiceClient<>(
                name,
                "http://" + nodeConfig.host() + ":" + nodeConfig.port(),
                serviceInterface
            );
            case LOCAL_ONLY -> throw new IllegalStateException(
                "Service '" + name + "' is LOCAL_ONLY but accessed from remote"
            );
        };
    }

    /** Gọi bởi Orchestrator khi service local READY → start transport server */
    public void startServerFor(String serviceName) {
        TransportType transport = config.getService(serviceName).preferredTransport();
        int port = config.getService(serviceName).port();

        switch (transport) {
            case GRPC, BOTH -> grpcServerManager.startServer(serviceName, port);
            case REST, BOTH -> restServerManager.startServer(serviceName, port);
            default -> { /* LOCAL_ONLY: nothing */ }
        }
    }

    public void stopServerFor(String serviceName) {
        grpcServerManager.stopServer(serviceName);
        restServerManager.stopServer(serviceName);
    }

    public RemoteServiceHealthChecker getHealthChecker(String serviceName) {
        NodeConfig node = config.getNodeConfig(serviceName);
        return new RemoteServiceHealthChecker(node.host(), node.port());
    }
}
```

---

## 5. ms-core — Client Implementations

### 5.1 `DirectServiceClient` — Same JVM

```java
package com.microjainslee.ms.core.client;

/**
 * Zero-overhead client khi service chạy cùng JVM.
 * Không serialize, không network, gọi method trực tiếp.
 * Thread-safe: T instance phải thread-safe (đây là RA — đã đảm bảo bởi core).
 */
public final class DirectServiceClient<T> implements SleeServiceClient<T> {

    private final String serviceName;
    private final T target;
    private volatile ServiceState currentState = ServiceState.READY;

    @Override
    public SleeResponse call(SleeRequest request) {
        if (currentState != ServiceState.READY) {
            throw new ServiceUnavailableException(serviceName, currentState);
        }
        // Gọi thẳng — reflection-free vì T là typed interface
        // APT generate adapter: DirectCallAdapter<T>
        return DirectCallAdapterRegistry.get(target.getClass())
                                        .invoke(target, request);
    }

    @Override
    public void notify(SleeEvent event) {
        // Async trên virtual thread mới — không block caller
        Thread.ofVirtual()
              .name("slee-notify-" + serviceName)
              .start(() -> call(SleeRequest.fromEvent(event)));
    }

    @Override public boolean isLocal()            { return true; }
    @Override public String targetServiceName()   { return serviceName; }
    @Override public ServiceState targetState()   { return currentState; }
}
```

### 5.2 `GrpcServiceClient` — Remote node

```java
package com.microjainslee.ms.grpc.client;

/**
 * gRPC client cho remote service.
 * Channel được pool bởi GrpcChannelPool (shared per host:port).
 * Dùng Quarkus gRPC client stubs — non-blocking với virtual thread park.
 *
 * Retry policy: exponential backoff, max 3 attempts.
 * Circuit breaker: mở sau 5 failures liên tiếp trong 10s window.
 */
public final class GrpcServiceClient<T> implements SleeServiceClient<T> {

    private final String serviceName;
    private final ManagedChannel channel;
    private final SleeServiceGrpc.SleeServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;
    private volatile ServiceState currentState = ServiceState.READY;

    // Generated từ slee_service.proto
    // (xem Section 6.1)

    @Override
    public SleeResponse call(SleeRequest request) {
        circuitBreaker.checkOpen(); // throws nếu circuit open

        try {
            SleeProto.SleeRequest protoReq = ProtoMapper.toProto(request);

            SleeProto.SleeResponse protoResp = stub
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .call(protoReq);

            circuitBreaker.recordSuccess();
            return ProtoMapper.fromProto(protoResp);

        } catch (StatusRuntimeException e) {
            circuitBreaker.recordFailure();

            if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                currentState = ServiceState.DEGRADED;
                throw new ServiceUnavailableException(serviceName, e);
            }
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new ServiceCallTimeoutException(serviceName, e);
            }
            throw new ServiceCallException(serviceName, e);
        }
    }

    @Override
    public void notify(SleeEvent event) {
        try {
            stub.withDeadlineAfter(1, TimeUnit.SECONDS)
                .notify(ProtoMapper.toProtoEvent(event));
        } catch (StatusRuntimeException e) {
            log.warn("notify() failed for {}: {}", serviceName, e.getMessage());
            // Fire-and-forget → swallow exception, log only
        }
    }

    @Override public boolean isLocal()           { return false; }
    @Override public String targetServiceName()  { return serviceName; }
    @Override public ServiceState targetState()  { return currentState; }
}
```

### 5.3 `NoOpServiceClient` — Optional dep absent

```java
package com.microjainslee.ms.core.client;

/**
 * Null Object pattern cho optional dependency không tồn tại.
 * call() luôn return SleeResponse với status NOT_AVAILABLE.
 * notify() là no-op.
 * Caller phải check targetState() == STOPPED trước khi dùng.
 */
public final class NoOpServiceClient<T> implements SleeServiceClient<T> {

    private final String serviceName;

    @Override
    public SleeResponse call(SleeRequest request) {
        return SleeResponse.notAvailable(request.correlationId(), serviceName);
    }

    @Override public void notify(SleeEvent event)    { /* intentional no-op */ }
    @Override public boolean isLocal()               { return false; }
    @Override public String targetServiceName()      { return serviceName; }
    @Override public ServiceState targetState()      { return ServiceState.STOPPED; }
}
```

---

## 6. ms-grpc — Transport Layer

### 6.1 Protobuf Schema

```protobuf
// slee_service.proto
// package: com.microjainslee.ms.grpc.proto
// java_package: com.microjainslee.ms.grpc.proto

syntax = "proto3";
package microjainslee;

option java_multiple_files = true;
option java_package = "com.microjainslee.ms.grpc.proto";

// ── Request / Response ──────────────────────────────────────────

message SleeRequest {
    string correlation_id  = 1;  // UUID v7
    string trace_id        = 2;  // OTel trace id
    string operation       = 3;  // vd: "sendSms", "querySs7"
    string caller_node     = 4;
    bytes  payload         = 5;  // Protobuf-encoded body (per-operation)
    map<string, string> headers = 6;
}

message SleeResponse {
    string correlation_id  = 1;
    StatusCode status      = 2;
    bytes  payload         = 3;
    string error_message   = 4;
}

message SleeEvent {
    string event_type      = 1;
    string source_service  = 2;
    bytes  payload         = 3;
    map<string, string> headers = 4;
}

message HealthRequest  {}
message HealthResponse {
    ServiceState state = 1;
    string node_id     = 2;
}

enum StatusCode {
    OK            = 0;
    ERROR         = 1;
    TIMEOUT       = 2;
    NOT_FOUND     = 3;
    NOT_AVAILABLE = 4;
}

enum ServiceState {
    REGISTERED    = 0;
    WAITING_DEPS  = 1;
    STARTING      = 2;
    READY         = 3;
    DEGRADED      = 4;
    STOPPING      = 5;
    STOPPED       = 6;
    FAILED        = 7;
}

// ── Service definition ──────────────────────────────────────────

service SleeService {
    // Synchronous request-response
    rpc Call   (SleeRequest) returns (SleeResponse);

    // Fire-and-forget (response ignored by caller)
    rpc Notify (SleeEvent)   returns (SleeResponse);

    // Health check (dùng bởi Orchestrator và K8s probe)
    rpc Health (HealthRequest) returns (HealthResponse);
}
```

### 6.2 `GrpcServiceServer` — Server phía local service

```java
package com.microjainslee.ms.grpc.server;

/**
 * gRPC server được start khi service READY ở CLUSTER mode.
 * Một server instance per service (per port).
 *
 * Routing: nhận SleeRequest → giải mã operation →
 *          gọi DirectCallAdapter → trả về SleeResponse
 *
 * Thread model: Quarkus gRPC dùng virtual thread per request.
 */
public final class GrpcServiceServer extends SleeServiceGrpc.SleeServiceImplBase {

    private final String serviceName;
    private final DirectCallAdapterRegistry adapterRegistry;
    private final ServiceStateProvider stateProvider;
    private Server server;

    public void start(int port) throws IOException {
        server = ServerBuilder.forPort(port)
            .addService(this)
            .executor(Executors.newVirtualThreadPerTaskExecutor()) // Java 25 VT
            .build()
            .start();

        log.info("gRPC server started for service '{}' on port {}", serviceName, port);
    }

    @Override
    public void call(SleeProto.SleeRequest request,
                     StreamObserver<SleeProto.SleeResponse> responseObserver) {
        try {
            SleeRequest domainReq = ProtoMapper.fromProto(request);
            SleeResponse domainResp = adapterRegistry
                .getAdapter(serviceName)
                .invoke(domainReq);

            responseObserver.onNext(ProtoMapper.toProto(domainResp));
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(
                Status.INTERNAL.withDescription(e.getMessage())
                               .withCause(e)
                               .asRuntimeException()
            );
        }
    }

    @Override
    public void notify(SleeProto.SleeEvent event,
                       StreamObserver<SleeProto.SleeResponse> responseObserver) {
        // Fire-and-forget: xử lý async, trả response ngay
        Thread.ofVirtual().start(() ->
            adapterRegistry.getAdapter(serviceName)
                           .invoke(SleeRequest.fromEvent(ProtoMapper.fromProto(event)))
        );
        responseObserver.onNext(SleeProto.SleeResponse.newBuilder()
            .setStatus(SleeProto.StatusCode.OK).build());
        responseObserver.onCompleted();
    }

    @Override
    public void health(SleeProto.HealthRequest request,
                       StreamObserver<SleeProto.HealthResponse> responseObserver) {
        responseObserver.onNext(SleeProto.HealthResponse.newBuilder()
            .setState(ProtoMapper.toProto(stateProvider.getState(serviceName)))
            .setNodeId(System.getenv("JAINSLEE_NODE_ID"))
            .build());
        responseObserver.onCompleted();
    }

    public void stop() {
        if (server != null) server.shutdown();
    }
}
```

---

## 7. ms-discovery — Health Check

```java
package com.microjainslee.ms.discovery;

/**
 * Kiểm tra health của remote service qua gRPC Health endpoint.
 * Dùng bởi Orchestrator khi chờ remote dep READY.
 *
 * Retry: exponential backoff 100ms → 200ms → 400ms → ... → max 5s
 */
public final class RemoteServiceHealthChecker {

    private final String host;
    private final int port;
    private ManagedChannel channel;
    private SleeServiceGrpc.SleeServiceBlockingStub stub;

    public RemoteServiceHealthChecker(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Block virtual thread cho đến khi remote service READY.
     * @throws ServiceStartupTimeoutException nếu vượt timeout
     */
    public void waitUntilReady(long timeoutMs) throws InterruptedException {
        ensureConnected();

        long deadline = System.currentTimeMillis() + timeoutMs;
        long backoff  = 100L; // ms

        while (System.currentTimeMillis() < deadline) {
            try {
                SleeProto.HealthResponse resp = stub
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .health(SleeProto.HealthRequest.getDefaultInstance());

                if (resp.getState() == SleeProto.ServiceState.READY) {
                    return; // ✅ READY
                }

            } catch (StatusRuntimeException e) {
                // Service chưa start hoặc port chưa mở → retry
                log.debug("Health check failed for {}:{}: {}", host, port, e.getMessage());
            }

            Thread.sleep(Math.min(backoff, deadline - System.currentTimeMillis()));
            backoff = Math.min(backoff * 2, 5_000L); // max 5s backoff
        }

        throw new ServiceStartupTimeoutException(
            "Remote service " + host + ":" + port +
            " not READY after " + timeoutMs + "ms"
        );
    }

    private void ensureConnected() {
        if (channel == null || channel.isShutdown()) {
            channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext() // TLS config từ DeploymentConfig nếu cần
                .build();
            stub = SleeServiceGrpc.newBlockingStub(channel);
        }
    }
}
```

---

## 8. APT Integration — Compile-time generation

### 8.1 `SleeServiceAnnotationProcessor` (trong jainslee-apt)

```java
/**
 * Xử lý @SleeService tại compile-time.
 * Sinh ra:
 *   1. {ClassName}SleeServiceDescriptor.java
 *   2. META-INF/services/com.microjainslee.ms.api.SleeServiceDescriptor
 *      (ServiceLoader entry)
 *   3. WARN nếu dependsOn reference không tồn tại trong compile unit
 *
 * Validation tại compile-time:
 *   - name() không được rỗng
 *   - Không được có @SleeService trên non-RA class
 *     (class phải implement ResourceAdaptorSpi hoặc extend AbstractRa)
 */
@SupportedAnnotationTypes("com.microjainslee.ms.api.annotation.SleeService")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class SleeServiceAnnotationProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
                           RoundEnvironment roundEnv) {

        Set<? extends Element> elements =
            roundEnv.getElementsAnnotatedWith(SleeService.class);

        List<String> descriptorClassNames = new ArrayList<>();

        for (Element element : elements) {
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@SleeService chỉ được đặt trên class",
                    element
                );
                continue;
            }

            TypeElement typeElement = (TypeElement) element;
            SleeService annotation = typeElement.getAnnotation(SleeService.class);

            // Validate
            if (annotation.name().isBlank()) {
                processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@SleeService.name() không được rỗng",
                    element
                );
                continue;
            }

            // Generate descriptor class
            String descriptorClassName = generateDescriptor(typeElement, annotation);
            descriptorClassNames.add(descriptorClassName);
        }

        // Ghi ServiceLoader file
        if (!descriptorClassNames.isEmpty()) {
            writeServiceLoaderFile(descriptorClassNames);
        }

        return true;
    }

    private String generateDescriptor(TypeElement type, SleeService ann) {
        String pkgName   = processingEnv.getElementUtils()
                                        .getPackageOf(type).getQualifiedName().toString();
        String className = type.getSimpleName() + "SleeServiceDescriptor";
        String fullName  = pkgName + "." + className;

        // Dùng JavaPoet để generate code sạch
        TypeSpec descriptorClass = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ClassName.get(SleeServiceDescriptor.class))
            .addMethod(MethodSpec.methodBuilder("name")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", ann.name())
                .build())
            .addMethod(MethodSpec.methodBuilder("transport")
                .addModifiers(Modifier.PUBLIC)
                .returns(TransportType.class)
                .addStatement("return $T.$L", TransportType.class, ann.transport())
                .build())
            .addMethod(MethodSpec.methodBuilder("dependsOn")
                .addModifiers(Modifier.PUBLIC)
                .returns(String[].class)
                .addStatement("return new String[]{$L}",
                    Arrays.stream(ann.dependsOn())
                          .map(s -> "\"" + s + "\"")
                          .collect(Collectors.joining(", ")))
                .build())
            // ... các method khác tương tự
            .addMethod(MethodSpec.methodBuilder("raClass")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(Class.class))
                .addStatement("return $T.class", ClassName.get(type))
                .build())
            .build();

        JavaFile javaFile = JavaFile.builder(pkgName, descriptorClass).build();
        try {
            javaFile.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR, "Cannot write descriptor: " + e.getMessage()
            );
        }

        return fullName;
    }

    private void writeServiceLoaderFile(List<String> classNames) {
        String spiFile = "META-INF/services/com.microjainslee.ms.api.SleeServiceDescriptor";
        try {
            FileObject fo = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", spiFile);
            try (Writer w = fo.openWriter()) {
                classNames.forEach(name -> {
                    try { w.write(name + "\n"); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR, "Cannot write SPI file: " + e.getMessage()
            );
        }
    }
}
```

---

## 9. Adapter Integration

### 9.1 Quarkus (`adapter-quarkus`)

```java
/**
 * CDI producer: load tất cả SleeServiceDescriptor qua ServiceLoader,
 * build graph, tạo Orchestrator, start.
 *
 * Lifecycle gắn với Quarkus StartupEvent / ShutdownEvent.
 */
@ApplicationScoped
public class QuarkusMicrosleeBootstrap {

    @Inject RaBootstrapPort raBootstrapPort;
    @Inject DeploymentConfig deploymentConfig;

    private MicrosleeServiceOrchestrator orchestrator;

    void onStart(@Observes StartupEvent ev) throws Exception {
        // 1. Load descriptors từ ServiceLoader (APT đã generate)
        List<SleeServiceDescriptor> descriptors =
            ServiceLoader.load(SleeServiceDescriptor.class)
                         .stream()
                         .map(ServiceLoader.Provider::get)
                         .collect(Collectors.toList());

        // 2. Build DAG — throws CircularDependencyException nếu có cycle
        ServiceDependencyGraph graph =
            ServiceDependencyGraph.build(descriptors);

        // 3. Log resolved order
        log.info("Service start order: {}", graph.resolveStartOrder());

        // 4. Create factory + orchestrator
        SleeServiceClientFactory factory = new SleeServiceClientFactory(
            deploymentConfig, serviceRegistry, grpcChannelPool
        );
        orchestrator = new MicrosleeServiceOrchestrator(
            graph, deploymentConfig, factory, raBootstrapPort
        );

        // 5. Start!
        orchestrator.startAll();
    }

    void onStop(@Observes ShutdownEvent ev) {
        orchestrator.stopAll();
    }
}
```

### 9.2 Spring Boot (`adapter-spring`)

```java
/**
 * SmartLifecycle implementation.
 * Phase cao hơn MicroJainsleeLifecycle để đảm bảo core start trước.
 */
@Component
public class SpringMicrosleeBootstrap implements SmartLifecycle {

    private final RaBootstrapPort raBootstrapPort;
    private final DeploymentConfig deploymentConfig;
    private MicrosleeServiceOrchestrator orchestrator;
    private volatile boolean running = false;

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 200; // sau MicroJainsleeLifecycle (MIN+100)
    }

    @Override
    public void start() {
        List<SleeServiceDescriptor> descriptors =
            ServiceLoader.load(SleeServiceDescriptor.class)
                         .stream()
                         .map(ServiceLoader.Provider::get)
                         .collect(Collectors.toList());

        ServiceDependencyGraph graph = ServiceDependencyGraph.build(descriptors);
        SleeServiceClientFactory factory = new SleeServiceClientFactory(
            deploymentConfig, serviceRegistry, grpcChannelPool
        );
        orchestrator = new MicrosleeServiceOrchestrator(
            graph, deploymentConfig, factory, raBootstrapPort
        );

        try {
            orchestrator.startAll();
            running = true;
        } catch (Exception e) {
            throw new IllegalStateException("Microservice bootstrap failed", e);
        }
    }

    @Override
    public void stop() {
        orchestrator.stopAll();
        running = false;
    }

    @Override public boolean isRunning()    { return running; }
    @Override public boolean isAutoStartup(){ return true; }
}
```

---

## 10. Ví dụ đầu đủ end-to-end

```java
// ── Developer viết thế này. Không quan tâm gì khác. ──

@SleeService(
    name     = "service-a",
    transport = TransportType.GRPC,
    dependsOn = {"service-b", "service-c"},
    startupTimeoutMs = 60_000
)
public class ServiceAResourceAdaptor extends AbstractRa {

    // Runtime inject đúng implementation (Direct hoặc gRPC)
    @SleeInject
    private SleeServiceClient<ServiceBContract> serviceB;

    @SleeInject
    private SleeServiceClient<ServiceCContract> serviceC;

    @Override
    public void onEvent(SleeEvent event, ActivityContext ac) {
        // Gọi B — không biết, không cần biết remote hay local
        SleeResponse resp = serviceB.call(
            SleeRequest.of("process", serialize(event))
        );

        if (resp.isOk()) {
            serviceC.notify(new SleeEvent("done", deserialize(resp.payload())));
        }
    }
}
```

```
Kịch bản 1 - SINGLE mode:
  deployment.yml: mode: single
  → serviceB = DirectServiceClient(serviceBInstance)
  → serviceB.call() = trực tiếp gọi ServiceB.handle()
  → Zero overhead

Kịch bản 2 - CLUSTER mode:
  deployment.yml: mode: cluster
  Node 1 (JAINSLEE_NODE_ID=node-1): start ServiceA
  Node 2 (JAINSLEE_NODE_ID=node-2): start ServiceB, ServiceC
  Node 3 (JAINSLEE_NODE_ID=node-3): start ServiceD

  Node 1 bootstrap:
    LOCAL: [service-a]
    REMOTE stubs: [service-b@node-2:9002, service-c@node-2:9003]
    → serviceB = GrpcServiceClient(10.0.0.2:9002)
    → serviceB.call() = gRPC call → node-2 → ServiceB.handle()

  Start order (DAG):
    Node-3: D starts (leaf)
    Node-2: C starts (leaf), then B starts (dep C,D satisfied)
    Node-1: A starts (dep B,C satisfied — via health check)
```

---

## 11. Exception Hierarchy

```
MicrosleeException (runtime base)
├── StartupException
│   ├── CircularDependencyException      "A → B → A"
│   ├── UnknownDependencyException       "depends on unknown: xyz"
│   ├── DuplicateServiceNameException    "@SleeService name conflict"
│   ├── ServiceStartupTimeoutException   "waited 30s for dep"
│   └── ServiceStartupException          "raActive() threw"
└── RuntimeException
    ├── ServiceUnavailableException      "target state: DEGRADED"
    ├── ServiceCallTimeoutException      "gRPC deadline exceeded"
    └── ServiceCallException             "unexpected gRPC error"
```

---

## 12. Summary — File list cho senior dev bắt đầu

**Tổng: ~20 files. Senior dev có thể implement trong 1-2 tuần.**

---

[USER] có thể dùng queue iniispant thay vì grpc/rest?

---

[ASSISTANT] Câu hỏi hay! Để tôi recall context kiến trúc hiện tại trước.

---

[ASSISTANT] Có context rồi. Câu trả lời: **Hoàn toàn được — và thậm chí là lựa chọn tốt hơn gRPC/REST trong nhiều trường hợp của micro-jainslee** vì bạn đã dùng Infinispan embedded (BOM 15.0.0.Final) trong jainslee-cluster rồi. Nhưng cần hiểu rõ trade-off.

---

## 🔍 So sánh: Infinispan Queue vs gRPC/REST

---

## 🏗️ Design: `TransportType.INFINISPAN_QUEUE`

### Ý tưởng cốt lõi

```
Node 1 (ServiceA)                    Node 2 (ServiceB)
     │                                     │
     │──put(request)──► [IspnQueue:"ss7"] ─►│
     │                                     │──process──►
     │◄──put(response)──[IspnQueue:"ss7.reply.corrId"]◄─│
     │                                     │
```

Infinispan **distributed cache + event listener** thay thế hoàn toàn gRPC transport.

---

## 📦 Thêm vào TransportType

```java
public enum TransportType {
    GRPC,
    REST,
    BOTH,
    LOCAL_ONLY,
    INFINISPAN_QUEUE   // ◄── THÊM MỚI
}
```

---

## 🔧 Design chi tiết: `ms-ispn` module

### Module layout bổ sung

```
jainslee-ms/
├── ms-api/
├── ms-core/
├── ms-grpc/
├── ms-rest/
├── ms-discovery/
└── ms-ispn/              ◄── MODULE MỚI
    ├── IspnQueueClient.java
    ├── IspnQueueServer.java
    ├── IspnQueueConfig.java
    └── IspnTransportManager.java
```

**Dependency rule:** `ms-ispn` → `ms-api`, `jainslee-cluster` (đã có Infinispan). KHÔNG import `jainslee-core`.

---

### Cấu trúc Queue trong Infinispan

```
Infinispan Cluster (shared giữa tất cả nodes)
│
├── Cache: "slee.queue.{serviceName}"          ← inbox của service
│   └── Key: UUID (entry ID)
│       Value: SleeQueueEntry (request)
│
├── Cache: "slee.reply.{correlationId}"        ← reply cho từng request
│   └── Key: correlationId
│       Value: SleeQueueEntry (response)
│       TTL: 30s (tự xóa sau khi consumed)
│
└── Cache: "slee.service.state"                ← health/state registry
    └── Key: serviceName
        Value: ServiceState + nodeId + timestamp
```

---

### `IspnQueueClient` — Thay thế GrpcServiceClient

```java
package com.microjainslee.ms.ispn;

/**
 * SleeServiceClient implementation dùng Infinispan distributed queue.
 *
 * Pattern:
 *   call()   → put request vào inbox queue → chờ reply cache → return
 *   notify() → put event vào inbox queue   → không chờ
 *
 * Thread model: virtual thread park tại reply.get(timeout)
 *               Infinispan blocking op → virtual thread friendly khi
 *               dùng Infinispan embedded (non-blocking listener available)
 */
public final class IspnQueueClient<T> implements SleeServiceClient<T> {

    private final String serviceName;
    private final Cache<String, SleeQueueEntry> inboxCache;   // target's inbox
    private final Cache<String, SleeQueueEntry> replyCache;   // reply store
    private final long callTimeoutMs;

    // Infinispan cache names (convention)
    static String inboxCacheName(String svc) { return "slee.queue." + svc; }
    static String replyCacheName()           { return "slee.reply"; }

    @Override
    public SleeResponse call(SleeRequest request) {
        String corrId = request.correlationId(); // UUID v7

        // 1. Put request vào inbox của target service
        SleeQueueEntry entry = SleeQueueEntry.ofRequest(request, corrId);
        inboxCache.put(corrId, entry);   // key = corrId để dễ trace

        // 2. Park virtual thread, chờ reply
        //    Virtual thread sẽ unmount khỏi OS thread khi block tại đây
        SleeQueueEntry reply = pollReply(corrId, callTimeoutMs);

        if (reply == null) {
            throw new ServiceCallTimeoutException(
                serviceName, callTimeoutMs
            );
        }

        return reply.toSleeResponse();
    }

    @Override
    public void notify(SleeEvent event) {
        // Fire-and-forget: chỉ put, không chờ reply
        String entryId = UUID.randomUUID().toString();
        SleeQueueEntry entry = SleeQueueEntry.ofEvent(event);
        inboxCache.put(entryId, entry);
        // No wait — return immediately
    }

    /**
     * Poll reply cache với timeout.
     * Dùng Infinispan CacheEntryCreatedListener để tránh busy-wait.
     */
    private SleeQueueEntry pollReply(String corrId, long timeoutMs) {
        // Option A: CompletableFuture + Infinispan listener (preferred)
        CompletableFuture<SleeQueueEntry> future = new CompletableFuture<>();

        // Register one-shot listener cho corrId cụ thể
        replyCache.addListener(new CorrIdListener(corrId, future));

        // Check nếu reply đã có sẵn (race condition safe)
        SleeQueueEntry existing = replyCache.get(corrId);
        if (existing != null) {
            future.complete(existing);
        }

        try {
            // Virtual thread park tại đây — không block OS thread
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null; // caller sẽ throw ServiceCallTimeoutException
        } finally {
            // Cleanup: xóa reply entry + remove listener
            replyCache.remove(corrId);
        }
    }

    @Override public boolean isLocal()          { return false; }
    @Override public String targetServiceName() { return serviceName; }
    @Override public ServiceState targetState() {
        // Đọc từ slee.service.state cache
        return stateCache.getOrDefault(serviceName, ServiceState.STOPPED);
    }
}
```

---

### `IspnQueueServer` — Consumer phía service nhận

```java
package com.microjainslee.ms.ispn;

/**
 * Chạy tại node có local service.
 * Listen vào inbox cache → xử lý → put reply vào reply cache.
 *
 * Threading: mỗi entry được xử lý trên một virtual thread riêng.
 * Back-pressure: Infinispan queue tự block khi đầy (max-entries config).
 */
@Listener(clustered = true, observation = Listener.Observation.POST)
public final class IspnQueueServer {

    private final String serviceName;
    private final Cache<String, SleeQueueEntry> inboxCache;
    private final Cache<String, SleeQueueEntry> replyCache;
    private final DirectCallAdapterRegistry adapterRegistry;
    private final ExecutorService vtExecutor =
        Executors.newVirtualThreadPerTaskExecutor();

    public void start() {
        // Listen tất cả new entries vào inbox của service này
        inboxCache.addListener(this);
        log.info("IspnQueueServer started for service '{}'", serviceName);
    }

    /**
     * Triggered khi có entry mới vào inbox cache.
     * Chạy trên Infinispan notification thread → dispatch ngay sang VT.
     */
    @CacheEntryCreated
    public void onEntryCreated(CacheEntryCreatedEvent<String, SleeQueueEntry> event) {
        if (event.isPre()) return; // chỉ xử lý POST event

        SleeQueueEntry entry = event.getValue();
        String entryKey      = event.getKey();

        // Dispatch sang virtual thread — không block notification thread
        vtExecutor.submit(() -> processEntry(entryKey, entry));
    }

    private void processEntry(String entryKey, SleeQueueEntry entry) {
        try {
            if (entry.isFireAndForget()) {
                // notify() path: xử lý nhưng không cần reply
                adapterRegistry.getAdapter(serviceName)
                               .invoke(entry.toSleeRequest());
                inboxCache.remove(entryKey); // cleanup

            } else {
                // call() path: xử lý + put reply
                SleeResponse response = adapterRegistry
                    .getAdapter(serviceName)
                    .invoke(entry.toSleeRequest());

                // Put reply vào reply cache với TTL 30s
                replyCache.put(
                    entry.correlationId(),
                    SleeQueueEntry.ofResponse(response),
                    30, TimeUnit.SECONDS  // TTL tự cleanup nếu caller timeout
                );
                inboxCache.remove(entryKey); // cleanup inbox
            }

        } catch (Exception e) {
            log.error("Error processing entry {} for service {}", entryKey, serviceName, e);

            if (!entry.isFireAndForget()) {
                // Put error response để caller không bị timeout
                replyCache.put(
                    entry.correlationId(),
                    SleeQueueEntry.ofError(entry.correlationId(), e.getMessage()),
                    30, TimeUnit.SECONDS
                );
            }
        }
    }

    public void stop() {
        inboxCache.removeListener(this);
        vtExecutor.shutdown();
    }
}
```

---

### `SleeQueueEntry` — Envelope trong cache

```java
package com.microjainslee.ms.ispn;

/**
 * Serializable envelope stored trong Infinispan cache.
 * Phải implement Externalizable hoặc dùng ProtoStream marshaller
 * (consistent với jainslee-cluster đã dùng ProtoStream).
 *
 * @ProtoField annotation tương thích với CmpFieldMapMarshaller pattern
 * đã có trong jainslee-cluster.
 */
@Proto  // Infinispan ProtoStream annotation
public final class SleeQueueEntry {

    @ProtoField(1) String correlationId;
    @ProtoField(2) EntryType type;        // REQUEST, RESPONSE, EVENT
    @ProtoField(3) String operation;
    @ProtoField(4) String callerNode;
    @ProtoField(5) byte[] payload;
    @ProtoField(6) String errorMessage;   // null nếu không phải ERROR
    @ProtoField(7) boolean fireAndForget;
    @ProtoField(8) Map<String, String> headers;

    public enum EntryType { REQUEST, RESPONSE, EVENT, ERROR }

    public static SleeQueueEntry ofRequest(SleeRequest req, String corrId) { ... }
    public static SleeQueueEntry ofResponse(SleeResponse resp) { ... }
    public static SleeQueueEntry ofEvent(SleeEvent event) { ... }
    public static SleeQueueEntry ofError(String corrId, String msg) { ... }

    public SleeRequest toSleeRequest() { ... }
    public SleeResponse toSleeResponse() { ... }
    public boolean isFireAndForget() { return fireAndForget; }
}
```

---

### `IspnTransportManager` — Quản lý cache lifecycle

```java
package com.microjainslee.ms.ispn;

/**
 * Khởi tạo và quản lý các Infinispan caches cho transport layer.
 * Reuse EmbeddedCacheManager từ jainslee-cluster — KHÔNG tạo mới.
 *
 * Cache configuration:
 *   - inbox:  DIST_SYNC, max-entries=10_000, no TTL (consumer xóa sau khi xử lý)
 *   - reply:  DIST_SYNC, max-entries=100_000, TTL=30s (tự cleanup)
 *   - state:  REPLICATED_SYNC, no TTL (service state luôn cần available)
 */
public final class IspnTransportManager {

    private final EmbeddedCacheManager cacheManager; // inject từ jainslee-cluster

    public <K,V> Cache<K,V> getOrCreateInboxCache(String serviceName) {
        String cacheName = IspnQueueClient.inboxCacheName(serviceName);

        if (!cacheManager.cacheExists(cacheName)) {
            ConfigurationBuilder cfg = new ConfigurationBuilder();
            cfg.clustering().cacheMode(CacheMode.DIST_SYNC)
               .memory().maxCount(10_000)
               .transaction().transactionMode(TransactionMode.NON_TRANSACTIONAL);
            cacheManager.defineConfiguration(cacheName, cfg.build());
        }

        return cacheManager.getCache(cacheName);
    }

    public <K,V> Cache<K,V> getOrCreateReplyCache() {
        String cacheName = IspnQueueClient.replyCacheName();

        if (!cacheManager.cacheExists(cacheName)) {
            ConfigurationBuilder cfg = new ConfigurationBuilder();
            cfg.clustering().cacheMode(CacheMode.DIST_SYNC)
               .memory().maxCount(100_000)
               .expiration().lifespan(30, TimeUnit.SECONDS) // TTL tự cleanup
               .transaction().transactionMode(TransactionMode.NON_TRANSACTIONAL);
            cacheManager.defineConfiguration(cacheName, cfg.build());
        }

        return cacheManager.getCache(cacheName);
    }

    public Cache<String, ServiceState> getStateCache() {
        // REPLICATED: mọi node đều có full copy → read luôn local
        ConfigurationBuilder cfg = new ConfigurationBuilder();
        cfg.clustering().cacheMode(CacheMode.REPL_SYNC);
        cacheManager.defineConfiguration("slee.service.state", cfg.build());
        return cacheManager.getCache("slee.service.state");
    }
}
```

---

## 🔄 Update `SleeServiceClientFactory`

```java
private <T> SleeServiceClient<T> buildClient(String name, Class<T> iface) {

    if (!config.hasService(name))
        return new NoOpServiceClient<>(name);

    if (config.isLocal(name)) {
        T instance = serviceRegistry.getByInterface(iface);
        return new DirectServiceClient<>(name, instance);
    }

    // Remote — chọn transport
    TransportType transport = config.getService(name).preferredTransport();

    return switch (transport) {
        case GRPC, BOTH  -> new GrpcServiceClient<>(name, ...);
        case REST        -> new RestServiceClient<>(name, ...);

        // ← THÊM MỚI
        case INFINISPAN_QUEUE -> new IspnQueueClient<>(
            name,
            ispnTransportManager.getOrCreateInboxCache(name),
            ispnTransportManager.getOrCreateReplyCache(),
            ispnTransportManager.getStateCache()
        );

        case LOCAL_ONLY  -> throw new IllegalStateException(...);
    };
}
```

---

## ⚖️ Khi nào dùng gì?

```
┌─────────────────────────────────────────────────────────────┐
│                   DECISION MATRIX                           │
├──────────────────────┬──────────────┬───────────────────────┤
│ Use case             │ Transport    │ Lý do                 │
├──────────────────────┼──────────────┼───────────────────────┤
│ SS7 signaling        │ ISPN_QUEUE   │ Ordering + back-pressure│
│ SMPP bulk SMS        │ ISPN_QUEUE   │ Async + high-throughput│
│ SIP dialog events    │ ISPN_QUEUE   │ AC state đã ở Infinispan│
│ HTTP-RA → external   │ REST         │ Browser/client access │
│ Real-time query      │ GRPC         │ Sub-ms latency cần    │
│ Cross-RA command     │ ISPN_QUEUE   │ Decoupled + reliable  │
│ Health check/probe   │ GRPC         │ K8s standard          │
└──────────────────────┴──────────────┴───────────────────────┘
```

---

## ⚠️ Trade-off cần biết

### ✅ Ưu điểm Infinispan Queue

- **Đã có sẵn** — `jainslee-cluster` BOM 15.0.0.Final đã là dependency
- **No extra port** — không cần expose gRPC/REST port per service
- **Auto-discovery** — Infinispan cluster tự biết các node, không cần `ms-discovery`
- **Back-pressure native** — queue đầy → put block → tự throttle
- **Ordering per-key** — dùng corrId làm key → đảm bảo ordering
- **AC state colocation** — `ActivityContext` đã ở Infinispan → request + state cùng chỗ

### ❌ Nhược điểm

- **Request-response phức tạp** — phải tự implement correlation với reply cache
- **Latency không deterministic** — phụ thuộc Infinispan cluster latency
- **Khó debug** — async → trace phức tạp hơn gRPC
- **Single point nếu Infinispan down** — gRPC/REST node-to-node vẫn chạy được

---

## 🚀 Khuyến nghị cuối cùng

```
@SleeService(name = "ss7",  transport = INFINISPAN_QUEUE) // ← async telecom
@SleeService(name = "smpp", transport = INFINISPAN_QUEUE) // ← bulk messaging
@SleeService(name = "sip",  transport = INFINISPAN_QUEUE) // ← AC colocation
@SleeService(name = "http", transport = REST)             // ← external facing
@SleeService(name = "mgmt", transport = GRPC)             // ← real-time ops
```

**Per-service transport** — mỗi RA tự chọn. `SleeServiceClientFactory` lo hết. Developer không đổi một dòng business code nào.

Bạn muốn tôi design chi tiết phần `CorrIdListener` (one-shot Infinispan listener) hay phần `ProtoStream marshaller` cho `SleeQueueEntry` tương thích với `CmpFieldMapMarshaller` hiện có?
