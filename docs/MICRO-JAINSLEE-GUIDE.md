# Hướng dẫn micro-jainslee cho Junior Developer

> Tài liệu này giải thích **micro-jainslee 1.1.0** hoạt động thế nào, cách tích hợp với **Quarkus**, và luồng code khi **fire event**.  
> Demo tham chiếu: `example/example-quarkus/` (đọc kỹ nhất), `example/example-embedded-j25/`, `example/grpc-simulator/`, `example/ussdgw-simulator/`.

---

## Mục lục

1. [JAIN SLEE là gì?](#1-jain-slee-là-gì)
2. [micro-jainslee khác gì Mobicents?](#2-micro-jainslee-khác-gì-mobicents)
3. [Các khái niệm cốt lõi](#3-các-khái-niệm-cốt-lõi)
4. [Cấu trúc module trong repo](#4-cấu-trúc-module-trong-repo)
5. [App có `main()` ở đâu?](#5-app-có-main-ở-đâu)
6. [Khởi động container — ai gọi `start()`?](#6-khởi-động-container--ai-gọi-start)
7. [example-quarkus — kiến trúc tổng quan](#7-example-quarkus--kiến-trúc-tổng-quan)
8. [Resource Adaptors (RA)](#8-resource-adaptors-ra)
9. [Events — pipeline đầy đủ](#9-events--pipeline-đầy-đủ)
10. [SBBs — ai làm gì?](#10-sbbs--ai-làm-gì)
11. [Profile, timer, CMP](#11-profile-timer-cmp)
12. [Luồng end-to-end (sequence diagram)](#12-luồng-end-to-end-sequence-diagram)
13. [Khi fire event, code nào chạy? (call stack)](#13-khi-fire-event-code-nào-chạy-call-stack)
14. [Tích hợp Quarkus ↔ JAIN SLEE](#14-tích-hợp-quarkus--jain-slee)
15. [SBB lifecycle và virtual thread](#15-sbb-lifecycle-và-virtual-thread)
16. [APT auto-deploy lúc compile](#16-apt-auto-deploy-lúc-compile)
17. [Bản đồ file quan trọng](#17-bản-đồ-file-quan-trọng)
18. [Chạy thử nhanh](#18-chạy-thử-nhanh)
19. [FAQ cho junior](#19-faq-cho-junior)

---

## 1. JAIN SLEE là gì?

**JAIN SLEE** (Java API for Integrated Networks — Service Logic Execution Environment) là chuẩn **môi trường thực thi logic dịch vụ viễn thông** theo mô hình **event-driven** (hướng sự kiện).

Thay vì viết một luồng `if/else` dài từ đầu đến cuối, bạn chia logic thành:

| Thành phần | Vai trò | Ví dụ trong demo USSD (example-quarkus) |
|-----------|---------|------------------------------------------|
| **SBB** (Service Building Block) | Khối xử lý business logic | `HttpServerSbb`, `Ss7UssdIngressSbb`, `GrpcClientSbb` |
| **Event** | Message nội bộ giữa các SBB / RA | `HttpUssdBeginEvent`, `Ss7UssdBeginEvent`, `GrpcMenuResponseEvent` |
| **RA** (Resource Adaptor) | Cầu nối với thế giới bên ngoài | `HttpIngressResourceAdaptor`, `GrpcMenuResourceAdaptor` |
| **ACI** (Activity Context Interface) | Context của một phiên/cuộc gọi | Một session USSD = một ACI (key = `sessionId`) |
| **Container (SLEE)** | Router event, quản lý SBB, timer, profile | `MicroSleeContainer` |

**Mô hình event-driven:**

```
Bên ngoài (HTTP/gRPC)  →  RA  →  fire Event  →  Container  →  SBB.onEvent()
SBB A                  →  fire Event khác  →  Container  →  SBB B.onEvent()
```

SBB **không gọi trực tiếp** SBB khác. Mọi giao tiếp qua **`container.routeEvent()`** (hoặc `UssdSbbWiring.routeEvent()` — wrapper mỏng).

---

## 2. micro-jainslee khác gì Mobicents?

| | Mobicents / RestComm (production USSD 7.3) | micro-jainslee (R&D) |
|---|---------------------------------------------|----------------------|
| Mục đích | Production gateway trên WildFly | Embed trong app Java/Quarkus/Spring |
| Phụ thuộc | JBoss, JMX, cluster HA… | Chỉ JVM + Disruptor |
| Chạy ở đâu | WildFly server | Trong process của app bạn |
| TCK / JSR-77 | Có (production path) | **Không** — R&D only |

> **Lưu ý production:** Demo trong `example/` **không được deploy** lên USSD gateway production. Production vẫn dùng Mobicents container.

---

## 3. Các khái niệm cốt lõi

### 3.1 SBB (Service Building Block)

Class Java implement `Sbb` + `SleeEventHandler`. Nhận event qua:

```java
@Override
public void onEvent(SleeEvent event, ActivityContextInterface aci) {
    if (event instanceof HttpUssdBeginEvent) {
        // xử lý...
    }
}
```

Lifecycle callbacks:

- `sbbCreate()` — tạo instance
- `sbbActivate()` — kích hoạt
- `sbbPassivate()` — tạm ngưng
- `sbbRemove()` — hủy

### 3.2 Event

POJO implement `SleeEvent`, annotate `@EventType`:

```java
@EventType(name = "HttpUssdBegin", vendor = "com.example.ussddemo.quarkus", version = "1.0")
public final class HttpUssdBeginEvent implements SleeEvent { ... }
```

APT scan `@EventType` lúc compile → ghi vào `sbb-index.properties`.

### 3.3 ACI (Activity Context Interface)

Đại diện **một phiên** USSD. Trong demo:

- Implementation: `InMemoryActivityContext`
- Key ACI = `sessionId` (UUID do HTTP RA sinh ra)
- Các SBB được **attach** vào ACI → cùng nhận event trên context đó

### 3.4 RA (Resource Adaptor)

Trong JAIN SLEE thật, RA lắng nghe SS7/SIP/HTTP và fire event vào container.

Trong **example-quarkus**, có **2 RA thật** (class Java implement `ResourceAdaptor`):

| RA | File | Vai trò |
|----|------|---------|
| HTTP ingress | `HttpIngressResourceAdaptor` | JDK `HttpServer` port **8080**, nhận POST từ `ussdgw-simulator` |
| gRPC menu | `GrpcMenuResourceAdaptor` | Gọi `grpc-simulator:9090`, fire request/response events |

**Không còn** REST Quarkus làm entry USSD. Quarkus REST (`HealthResource`) chỉ phục vụ admin trên port **18080**.

### 3.5 Deployable Unit (DU)

```java
@DeployableUnit(
    name = "UssdGatewayDemo",
    sbbs = { HttpServerSbb.class, Ss7UssdIngressSbb.class, GrpcClientSbb.class })
public final class UssdGatewayDemoDu { }
```

---

## 4. Cấu trúc module trong repo

```
jain-slee/jain-slee/
├── jainslee-api/          # Interface: Sbb, SleeEvent, TimerPort, annotations...
├── jainslee-core/         # MicroSleeContainer, EventRouter, pools...
├── jainslee-apt/          # Annotation processor → sbb-index.properties
├── jainslee-scheduler/    # Timer (HashedWheelTimer)
├── adapters/
│   ├── adapter-quarkus/   # Quarkus CDI extension
│   └── adapter-springboot/
└── example/
    ├── example-quarkus/       # Quarkus + adapter-quarkus  ← đọc file này trước
    ├── example-embedded-j25/  # Plain Java 25
    ├── example-spring/        # Spring Boot
    ├── grpc-simulator/        # gRPC AS ngoài process (:9090)
    └── ussdgw-simulator/      # HTTP client giả USSD GW
```

**Trái tim runtime:** `jainslee-core/.../MicroSleeContainer.java` + `EventRouter.java`

---

## 5. App có `main()` ở đâu?

**micro-jainslee không có `main()` riêng.** Nó là **thư viện embed** — app host gọi `MicroSleeContainer.start()`.

| Variant | Entry point | Ai start SLEE? |
|---------|-------------|----------------|
| **example-embedded-j25** | `EmbeddedUssdMain.main()` | Gọi trực tiếp trong `main()` |
| **example-quarkus** | Quarkus bootstrap | `UssdDemoBootstrap.onStart(StartupEvent)` |
| **example-spring** | `SpringApplication.run(...)` | `UssdDemoBootstrap` `@PostConstruct` |
| **ussdgw-simulator** | `Ss7UssdSimulatorMain.main()` | **Không** chạy SLEE — chỉ HTTP client |
| **grpc-simulator** | `GrpcSimulatorMain.main()` | **Không** chạy SLEE — chỉ gRPC server |

### Quarkus — không có `main()` viết tay

Quarkus dùng `quarkus-maven-plugin` generate bootstrap. SLEE + RA start qua CDI observer:

```java
// UssdDemoBootstrap.java
void onStart(@Observes StartupEvent ev) {
    microSleeContainer().start();
    wiring.install(...);
    registerSbbTypes(c);
    seedProfiles(c);
    bootstrapResourceAdaptors(c, new GrpcMenuClient(grpcHost, grpcPort));
}
```

---

## 6. Khởi động container — ai gọi `start()`?

`MicroSleeContainer.start()` làm:

1. Prewarm pool SBB virtual thread
2. Set state = `STARTED`
3. **`autoDeployFromClasspathIndex()`** — đọc `META-INF/microjainslee/sbb-index.properties`

```
container.start()
    ├── sbbEntityPool.prewarm()
    └── autoDeployFromClasspathIndex()
            ├── đọc sbb-index.properties (APT generate lúc compile)
            ├── instantiate SBB classes (@SbbAnnotation)
            ├── registerSbb(name, instance)
            └── activate DeployableUnit services
```

Sau khi start, container sẵn sàng nhận `routeEvent()`.

**Lưu ý demo:** Mỗi session USSD vẫn **register SBB thủ công** trong `UssdSbbWiring.beginUssdSession()` vì cần inject `UssdSbbWiring` qua constructor. `registerSbbType()` đăng ký factory cho pooling tương lai / `acquireEntity()`.

---

## 7. example-quarkus — kiến trúc tổng quan

Demo mô phỏng **USSD Gateway** bên trong SLEE. **Không có SS7 wire thật** — `ussdgw-simulator` đóng vai GW bên ngoài qua HTTP.

```
┌─────────────────────────────────────────────────────────────────────┐
│  ussdgw-simulator (HTTP client, ngoài process)                      │
│       POST /api/ussd/begin-callback?callbackUrl=...                 │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ HTTP :8080
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  HttpIngressResourceAdaptor  (HTTP RA — JDK HttpServer)             │
│       wiring.beginUssdSession() → create ACI + attach SBBs          │
│       container.routeEvent(HttpUssdBeginEvent)                      │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ Disruptor EventRouter
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  HttpServerSbb (priority 8)                                         │
│    → setTimer (session timeout)                                     │
│    → routeEvent(Ss7UssdBeginEvent)                                  │
└───────────────────────────────┬─────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Ss7UssdIngressSbb (priority 7) — internal MAP/USSD leg             │
│    → CMP write (sessionId, msisdn, menuTier)                        │
│    → grpcRa().requestMenu()                                         │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  GrpcMenuResourceAdaptor (gRPC RA)                                  │
│    → routeEvent(GrpcMenuRequestEvent)  ← GrpcClientSbb observes     │
│    → async gRPC call → grpc-simulator:9090                          │
│    → routeEvent(GrpcMenuResponseEvent)                              │
└───────────────────────────────┬─────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Ss7UssdIngressSbb                                                    │
│    → build USSD text → routeEvent(UssdCompleteEvent)                │
└───────────────────────────────┬─────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  HttpServerSbb → completeSession() → UssdCallbackDispatcher         │
│       async POST callbackUrl → ussdgw-simulator                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Package layout (example-quarkus)

```
com.example.ussddemo.quarkus/
├── bootstrap/UssdDemoBootstrap.java   ← CDI startup: container, RA, profile seed
├── service/
│   ├── UssdSbbWiring.java           ← cầu nối RA ↔ SBB ↔ container
│   ├── UssdSessionStore.java        ← trạng thái session (PROCESSING/COMPLETED)
│   └── UssdCallbackDispatcher.java  ← HTTP callback async
├── ra/
│   ├── HttpIngressResourceAdaptor.java
│   └── GrpcMenuResourceAdaptor.java
├── sbbs/
│   ├── HttpServerSbb.java
│   ├── Ss7UssdIngressSbb.java
│   └── GrpcClientSbb.java
├── events/                          ← 5 event types
├── profile/UssdSubscriberProfile.java
├── grpc/GrpcMenuClient.java         ← client tới grpc-simulator
├── rest/HealthResource.java         ← Quarkus admin only (:18080)
└── du/UssdGatewayDemoDu.java
```

---

## 8. Resource Adaptors (RA)

### 8.1 HttpIngressResourceAdaptor — HTTP ingress RA

**File:** `ra/HttpIngressResourceAdaptor.java`

| Thuộc tính | Giá trị |
|------------|---------|
| Port | `ussd.http.port` = **8080** |
| Transport | JDK `com.sun.net.httpserver.HttpServer` |
| Implements | `ResourceAdaptor` |

**Endpoints:**

| Method | Path | Mô tả |
|--------|------|-------|
| POST | `/api/ussd/begin-callback?callbackUrl=...` | Bắt đầu session + callback bất đồng bộ |
| POST | `/api/ussd/begin` | Bắt đầu session (polling qua GET session) |
| GET | `/api/ussd/sessions/{id}` | Trạng thái session |
| GET | `/health` | Health check |

**Luồng khi nhận POST:**

```
1. Parse JSON body → msisdn, ussdString
2. Sinh sessionId = UUID
3. wiring.beginUssdSession(sessionId, msisdn, ussdString, callbackUrl)
4. Trả 202 Accepted + JSON {"sessionId":"...","status":"PROCESSING"}
```

HTTP RA delegate sang `UssdSbbWiring.beginUssdSession()` — tạo ACI, attach SBB, fire `HttpUssdBeginEvent`.

### 8.2 GrpcMenuResourceAdaptor — gRPC RA

**File:** `ra/GrpcMenuResourceAdaptor.java`

| Thuộc tính | Giá trị |
|------------|---------|
| Upstream | `GrpcMenuClient` → `grpc.host:grpc.port` (default `127.0.0.1:9090`) |
| Worker pool | Virtual threads (`newVirtualThreadPerTaskExecutor`) |

**Method chính:** `requestMenu(sessionId, msisdn, ussdString)`

```
1. Lookup ACI theo sessionId
2. routeEvent(GrpcMenuRequestEvent)  → GrpcClientSbb.onEvent() log/observe
3. workerPool.submit → client.resolveMenu()  [async, không block SBB thread]
4. routeEvent(GrpcMenuResponseEvent) → Ss7UssdIngressSbb.onEvent()
```

Đây là pattern RA điển hình: **fire event trước** (request), **gọi I/O bất đồng bộ**, **fire event sau** (response).

### 8.3 RA lifecycle

Cả hai RA đều bootstrap qua `RaBootstrapContextImpl`:

```java
RaBootstrapContextImpl ctx = new RaBootstrapContextImpl(container, "HttpIngressRA");
ctx.setResourceAdaptor(httpRa);
httpRa.setResourceAdaptorContext(ctx);
httpRa.raConfigure();
httpRa.raActive();   // HTTP RA: start HttpServer tại đây
```

Shutdown (`ShutdownEvent`): `raInactive()` → `raUnconfigure()` → `container.stop()`.

---

## 9. Events — pipeline đầy đủ

Demo có **5 event types**. Chúng tạo thành pipeline một chiều:

```
HttpUssdBeginEvent
    → Ss7UssdBeginEvent
        → GrpcMenuRequestEvent  (RA fire, không qua SBB chain)
        → GrpcMenuResponseEvent (RA fire sau gRPC)
    → UssdCompleteEvent
```

### Bảng chi tiết

| # | Event | Ai fire | Ai xử lý | Hành động |
|---|-------|---------|----------|-----------|
| 1 | `HttpUssdBeginEvent` | `UssdSbbWiring.beginUssdSession()` | `HttpServerSbb` | Log begin, **set session timer**, route sang SS7 leg |
| 2 | `Ss7UssdBeginEvent` | `HttpServerSbb` | `Ss7UssdIngressSbb` | **CMP write**, gọi `grpcRa().requestMenu()` |
| 3 | `GrpcMenuRequestEvent` | `GrpcMenuResourceAdaptor` | `GrpcClientSbb` | Log/observe (child SBB — RA thực hiện gRPC call) |
| 4 | `GrpcMenuResponseEvent` | `GrpcMenuResourceAdaptor` | `Ss7UssdIngressSbb` | Ghép USSD text từ menu + tier → fire complete |
| 5 | `UssdCompleteEvent` | `Ss7UssdIngressSbb` | `HttpServerSbb` | `completeSession()` → callback HTTP |

### Code mẫu từng event

**HttpUssdBeginEvent** — chứa profile tier đã resolve:

```java
// events/HttpUssdBeginEvent.java
@EventType(name = "HttpUssdBegin", ...)
public final class HttpUssdBeginEvent implements SleeEvent {
    // sessionId, msisdn, ussdString, callbackUrl, menuTier
}
```

**Ss7UssdBeginEvent** — internal MAP leg (không có callbackUrl):

```java
// HttpServerSbb.onHttpBegin()
wiring.routeEvent(new Ss7UssdBeginEvent(
    event.getSessionId(), event.getMsisdn(), event.getUssdString(),
    event.getMenuTier()), aci);
```

**GrpcMenuResponseEvent → UssdCompleteEvent:**

```java
// Ss7UssdIngressSbb.onGrpcResponse()
String ussdText = "USSD menu for session " + event.getSessionId()
    + " (tier " + tier + "):\n" + menu;
wiring.routeEvent(new UssdCompleteEvent(event.getSessionId(), ussdText), aci);
```

---

## 10. SBBs — ai làm gì?

### 10.1 HttpServerSbb — GW-facing entry

**File:** `sbbs/HttpServerSbb.java`  
**Priority:** 8 (cao nhất trên ACI)  
**SBB ID per session:** `{sessionId}/http`

| Event nhận | Hành động |
|------------|-----------|
| `HttpUssdBeginEvent` | Set timer qua `TimerPort`, route `Ss7UssdBeginEvent` |
| `UssdCompleteEvent` | `wiring.completeSession()` → callback |
| `TimerFiredEvent` | Session timeout → `failSession()` |

Đây là SBB **đối diện với HTTP GW** — normalize session, quản lý timer, hoàn tất callback.

### 10.2 Ss7UssdIngressSbb — internal MAP/USSD leg

**File:** `sbbs/Ss7UssdIngressSbb.java`  
**Priority:** 7  
**SBB ID:** `{sessionId}/ss7`  
**Extends:** `CmpBackedSbb` (CMP fields)

| Event nhận | Hành động |
|------------|-----------|
| `Ss7UssdBeginEvent` | CMP write, delegate menu lookup tới gRPC RA |
| `GrpcMenuResponseEvent` | Build USSD response text, fire `UssdCompleteEvent` |

**Vai trò quan trọng:** Trong production, SBB này nhận event từ **SS7 RA** (`MAP-Open`, `MAP-Process-Unstructured-SS-Request`). Trong demo, nó nhận `Ss7UssdBeginEvent` từ `HttpServerSbb` vì GW bên ngoài chỉ gửi HTTP.

```
ussdgw-simulator  ≈  external SS7/USSD GW (HTTP only)
HttpServerSbb     ≈  GW normalization layer
Ss7UssdIngressSbb ≈  MAP USSD service logic (inside SLEE)
```

### 10.3 GrpcClientSbb — child SBB

**File:** `sbbs/GrpcClientSbb.java`  
**Priority:** 5  
**SBB ID:** `{sessionId}/grpc`

| Event nhận | Hành động |
|------------|-----------|
| `GrpcMenuRequestEvent` | Log/observe — **gRPC call thực tế do RA thực hiện** |

Child SBB minh họa pattern JAIN SLEE: RA fire event, child SBB subscribe để audit/hook. Logic gRPC nằm trong `GrpcMenuResourceAdaptor`.

### 10.4 Session wiring — attach 3 SBB trên một ACI

**File:** `service/UssdSbbWiring.java` → `beginUssdSession()`

```java
InMemoryActivityContext aci = c.createActivityContext(sessionId);

SimpleSbbLocalObject http  = c.registerSbb(sessionId + "/http",  new HttpServerSbb(this));
SimpleSbbLocalObject ss7   = c.registerSbb(sessionId + "/ss7",   new Ss7UssdIngressSbb(this));
SimpleSbbLocalObject grpc  = c.registerSbb(sessionId + "/grpc",  new GrpcClientSbb(this));
http.setPriority(8);
ss7.setPriority(7);
grpc.setPriority(5);
c.attach(sessionId, http);
c.attach(sessionId, ss7);
c.attach(sessionId, grpc);

c.routeEvent(new HttpUssdBeginEvent(...), aci);
```

---

## 11. Profile, timer, CMP

### 11.1 Profile — UssdSubscriberProfile

**File:** `profile/UssdSubscriberProfile.java`  
**Table:** `ussd-subscriber`

Bootstrap seed 2 subscriber:

| MSISDN | menuTier | Ý nghĩa demo |
|--------|----------|--------------|
| `251911000001` | 1 (GOLD) | Tier cao |
| `251911000002` | 2 (SILVER) | Tier thấp |

```java
// UssdDemoBootstrap.seedProfiles()
ProfileLocalObject plo = facility.createProfile(TABLE_NAME, msisdn, ...);
sub.setMenuTier(tier);
wiring.seedMenuTier(msisdn, tier);  // cache cho beginUssdSession()
```

Khi session bắt đầu, `resolveMenuTier(msisdn)` đưa tier vào `HttpUssdBeginEvent` → SS7 SBB dùng trong response text.

### 11.2 Timer — session timeout

`HttpServerSbb` set timer khi nhận `HttpUssdBeginEvent`:

```java
long timerId = wiring.container().getTimerPort()
    .setTimer(wiring.sessionTimeoutMs(), httpLocal);  // default 30s
wiring.rememberTimer(sessionId, timerId);
```

Timeout → `TimerFiredEvent` → `HttpServerSbb.onTimer()` → `failSession("session timeout")`.

Config: `ussd.session.timeout-ms=30000`

### 11.3 CMP — Ss7UssdIngressSbb

`Ss7UssdIngressSbb` extends `CmpBackedSbb` với 3 CMP fields:

| Field | Type | Set khi |
|-------|------|---------|
| `sessionId` | String | `Ss7UssdBeginEvent` |
| `msisdn` | String | `Ss7UssdBeginEvent` |
| `menuTier` | int | `Ss7UssdBeginEvent` |

```java
cmpWrite(method("setSessionId", String.class), event.getSessionId());
// ...
Object tierObj = cmpRead(method("getMenuTier"));  // đọc lại khi build response
```

CMP persist state trên SBB entity — pattern giống Mobicents khi dialog MAP kéo dài nhiều bước.

---

## 12. Luồng end-to-end (sequence diagram)

Scenario: `ussdgw-simulator` gọi USSD `*123#` qua callback.

```mermaid
sequenceDiagram
    participant Sim as ussdgw-simulator
    participant HRA as HttpIngress RA :8080
    participant W as UssdSbbWiring
    participant C as MicroSleeContainer
    participant ER as EventRouter
    participant HTTP as HttpServerSbb
    participant SS7 as Ss7UssdIngressSbb
    participant GRA as GrpcMenu RA
    participant GRPC as GrpcClientSbb
    participant AS as grpc-simulator :9090
    participant CB as UssdCallbackDispatcher

    Sim->>HRA: POST /api/ussd/begin-callback?callbackUrl=...
    HRA->>W: beginUssdSession(sessionId, msisdn, ussd, callbackUrl)
    W->>C: createActivityContext + registerSbb×3 + attach
    W->>C: routeEvent(HttpUssdBeginEvent)
    HRA-->>Sim: 202 Accepted

    C->>ER: routeEvent
    ER->>HTTP: onEvent(HttpUssdBeginEvent) [VT, priority 8]
    HTTP->>HTTP: setTimer(30s)
    HTTP->>W: routeEvent(Ss7UssdBeginEvent)
    W->>C: routeEvent
    C->>ER: routeEvent
    ER->>SS7: onEvent(Ss7UssdBeginEvent) [priority 7]
    SS7->>SS7: CMP write
    SS7->>GRA: requestMenu()

    GRA->>C: routeEvent(GrpcMenuRequestEvent)
    C->>ER: routeEvent
    ER->>GRPC: onEvent(GrpcMenuRequestEvent) [priority 5]
    GRA->>AS: gRPC ResolveMenu [async VT]
    AS-->>GRA: MenuResponse
    GRA->>C: routeEvent(GrpcMenuResponseEvent)
    C->>ER: routeEvent
    ER->>SS7: onEvent(GrpcMenuResponseEvent)
    SS7->>W: routeEvent(UssdCompleteEvent)
    W->>C: routeEvent
    C->>ER: routeEvent
    ER->>HTTP: onEvent(UssdCompleteEvent)
    HTTP->>W: completeSession()
    W->>CB: dispatch(callbackUrl) [async HTTP POST]
    CB-->>Sim: callback JSON COMPLETED
```

---

## 13. Khi fire event, code nào chạy? (call stack)

Giả sử `Ss7UssdIngressSbb` vừa nhận `GrpcMenuResponseEvent` và fire `UssdCompleteEvent`:

### Bước 1 — SBB fire event tiếp

```java
// Ss7UssdIngressSbb.onGrpcResponse()
wiring.routeEvent(new UssdCompleteEvent(event.getSessionId(), ussdText), aci);
```

### Bước 2 — Wiring chuyển sang container

```java
// UssdSbbWiring.routeEvent()
container.routeEvent(event, aci);
```

### Bước 3 — Container → EventRouter

```java
// MicroSleeContainer.routeEvent()
eventRouter.routeEvent(event, aci);
```

### Bước 4 — Disruptor publish

```java
// EventRouter.routeEvent()
ringBuffer.next();
wrapper.setEvent(event);
wrapper.setAci(aci);
ringBuffer.publish(sequence);
```

### Bước 5 — Dispatch tới SBB

```java
// EventRouter.dispatchWithTransaction()
1. Lấy SBB đã attach trên ACI
2. Sort priority DESC (HTTP=8, SS7=7, gRPC child=5)
3. EventMask filter
4. deliverEvent() → submit Runnable lên VT của SBB entity
5. handler.onEvent(event, aci)  // HttpServerSbb.onComplete()
```

**Tóm tắt thread:**

| Thread | Chạy gì |
|--------|---------|
| HTTP RA VT | Nhận POST từ simulator |
| Disruptor worker | `EventRouter.dispatch()` |
| SBB virtual thread | `HttpServerSbb`, `Ss7UssdIngressSbb`, `GrpcClientSbb` |
| gRPC RA VT | `GrpcMenuClient.resolveMenu()` |
| Callback VT | `UssdCallbackDispatcher.post()` |

---

## 14. Tích hợp Quarkus ↔ JAIN SLEE

```
┌─────────────────────────────────────────────────────────────┐
│ LỚP 1: Quarkus CDI                                          │
│   UssdDemoBootstrap  →  UssdSbbWiring  →  UssdSessionStore    │
│   HealthResource (:18080 admin only)                        │
├─────────────────────────────────────────────────────────────┤
│ LỚP 2: Resource Adaptors (trong example app)                │
│   HttpIngressResourceAdaptor (:8080)                        │
│   GrpcMenuResourceAdaptor → grpc-simulator:9090             │
├─────────────────────────────────────────────────────────────┤
│ LỚP 3: adapter-quarkus (CDI extension)                      │
│   MicroJainsleeProcessor / MicroJainsleeRecorder            │
│   → optional @Produces MicroSleeContainer                     │
├─────────────────────────────────────────────────────────────┤
│ LỚP 4: jainslee-core                                        │
│   MicroSleeContainer → EventRouter → SBB.onEvent()          │
└─────────────────────────────────────────────────────────────┘
```

### Config (`application.properties`)

```properties
# USSD traffic — HTTP RA (ussdgw-simulator targets this)
ussd.http.port=8080
ussd.session.timeout-ms=30000

# gRPC backend
grpc.host=127.0.0.1
grpc.port=9090

# Quarkus admin only
quarkus.http.port=18080

# Container tuning
microjainslee.buffer-size=2048
microjainslee.prefer-virtual-threads=true
microjainslee.sbb-pool-min=16
microjainslee.sbb-pool-max=4096
```

### Ranh giới Quarkus vs SLEE

| Thuộc Quarkus (CDI) | Thuộc SLEE (core) |
|--------------------|-------------------|
| `UssdDemoBootstrap` | `MicroSleeContainer` |
| `UssdSessionStore` | `EventRouter` |
| `UssdCallbackDispatcher` | `registerSbb`, `attach`, `routeEvent` |
| `HealthResource` | `InMemoryActivityContext`, `TimerPort` |
| `@ConfigProperty` | `ProfileFacility` |

**`UssdSbbWiring`** là cầu nối chính giữa RA, SBB và container.

---

## 15. SBB lifecycle và virtual thread

Mỗi `registerSbb(id, ...)` tạo một **SbbEntity** với virtual thread riêng. Mọi `onEvent()` của SBB đó chạy **tuần tự** trên VT đó (đúng spec JAIN SLEE).

### Priority trên ACI

```java
http.setPriority(8);   // HttpServerSbb — nhận trước
ss7.setPriority(7);    // Ss7UssdIngressSbb
grpc.setPriority(5);   // GrpcClientSbb
```

### Per-session SBB ID

```java
"{sessionId}/http"
"{sessionId}/ss7"
"{sessionId}/grpc"
```

Tránh collision khi nhiều session concurrent.

---

## 16. APT auto-deploy lúc compile

Khi `mvn compile`, `jainslee-apt` scan `@SbbAnnotation`, `@EventType`, `@DeployableUnit` → `sbb-index.properties`.

Ví dụ:

```properties
sbb.0.class=...HttpServerSbb
sbb.1.class=...Ss7UssdIngressSbb
sbb.2.class=...GrpcClientSbb
eventType.0.class=...HttpUssdBeginEvent
du.0.class=...UssdGatewayDemoDu
```

`container.start()` → `autoDeployFromClasspathIndex()` instantiate global SBB instances.

**Demo vẫn register per-session** trong `beginUssdSession()` vì cần `UssdSbbWiring` qua constructor.

---

## 17. Bản đồ file quan trọng

### example-quarkus — đọc theo thứ tự

| # | File | Đọc để hiểu |
|---|------|-------------|
| 1 | `bootstrap/UssdDemoBootstrap.java` | Startup: container, RA, profile, registerSbbType |
| 2 | `service/UssdSbbWiring.java` | Session wiring, begin/complete/fail |
| 3 | `ra/HttpIngressResourceAdaptor.java` | HTTP entry (port 8080) |
| 4 | `ra/GrpcMenuResourceAdaptor.java` | gRPC async RA |
| 5 | `sbbs/HttpServerSbb.java` | GW-facing SBB + timer |
| 6 | `sbbs/Ss7UssdIngressSbb.java` | Internal MAP leg + CMP |
| 7 | `sbbs/GrpcClientSbb.java` | Child SBB observe gRPC request |
| 8 | `events/*.java` | 5 event types |
| 9 | `profile/UssdSubscriberProfile.java` | Profile seed |
| 10 | `service/UssdCallbackDispatcher.java` | Async HTTP callback |

### Core (engine)

| File | Vai trò |
|------|---------|
| `jainslee-core/.../MicroSleeContainer.java` | Container: start/stop, registerSbb, attach, routeEvent |
| `jainslee-core/.../EventRouter.java` | Disruptor router, dispatch, priority |
| `jainslee-core/.../VirtualThreadSbbEntityPool.java` | 1 VT / SBB id |
| `jainslee-core/.../RaBootstrapContextImpl.java` | RA bootstrap + `SleeEndpointPort` |

---

## 18. Chạy thử nhanh

### Full demo (3 terminal)

```bash
# 0. Install micro-jainslee + adapter (once)
cd jain-slee/jain-slee
mvn -B -ntp install -DskipTests \
  -pl jainslee-api,jainslee-scheduler,jainslee-core,jainslee-apt,adapters/adapter-quarkus -am

# Terminal A — gRPC AS (bắt buộc)
cd example/grpc-simulator
mvn -B -ntp package
java -cp target/grpc-simulator.jar:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
  com.example.grpcsimulator.GrpcSimulatorMain 9090

# Terminal B — Quarkus (HTTP RA on 8080)
cd example/example-quarkus
mvn -B -ntp quarkus:dev -Dquarkus.build.skip=false

# Terminal C — USSD GW simulator
cd example/ussdgw-simulator
mvn -B -ntp package
java -jar target/ussdgw-simulator-1.0.0-SNAPSHOT.jar \
  http://127.0.0.1:8080 251911000001 '*123#'
```

Kỳ vọng: `202 Accepted`, callback vài giây sau với `COMPLETED` và menu chứa `Balance`.

### Chạy test (không cần grpc-simulator bên ngoài)

```bash
cd example/example-quarkus && mvn -B -ntp test    # 2 tests — HTTP RA E2E
cd example/example-embedded-j25 && mvn -B -ntp test  # 7 tests
```

Chi tiết EN/VI: xem `example/README.md` và README từng project.

---

## 19. FAQ cho junior

### Q: Tại sao không dùng Quarkus REST cho USSD?

Để minh họa **Resource Adaptor pattern** đúng JAIN SLEE: HTTP vào SLEE qua RA, không qua REST layer gọi thẳng `routeEvent()`. Quarkus REST chỉ còn `HealthResource` trên port 18080.

### Q: Ss7UssdIngressSbb có phải SS7 thật không?

**Không.** Đây là SBB **logic MAP/USSD nội bộ**. Trong production nhận event từ SS7 RA. Demo nhận `Ss7UssdBeginEvent` từ `HttpServerSbb` vì GW bên ngoài (`ussdgw-simulator`) chỉ gửi HTTP.

### Q: GrpcClientSbb làm gì nếu RA đã gọi gRPC?

Child SBB **observe** `GrpcMenuRequestEvent` — pattern JAIN SLEE cho phép nhiều SBB subscribe cùng event. Logic I/O nằm trong RA; SBB có thể thêm audit, metrics, fallback.

### Q: SBB có được `@Inject` không?

Trong demo: SBB được `new` trong `beginUssdSession()`, nhận `UssdSbbWiring` qua constructor. CDI inject `UssdSbbWiring`, `UssdSessionStore` — không inject trực tiếp vào SBB instance.

### Q: `routeEvent` sync hay async?

- Publish vào Disruptor: **async** (non-blocking cho HTTP RA thread)
- `deliverEvent` **await** SBB xử lý trên VT (sync handoff, timeout 30s)
- gRPC call: **async** trên VT riêng của RA
- Callback HTTP: **async** trên VT riêng

### Q: Quarkus example có `@QuarkusTest` không?

Chưa — Quarkus 3.15.1 ASM chỉ đọc bytecode Java 21. Test dùng wiring test boot container + HTTP RA trực tiếp. Upgrade Quarkus 3.17+ cho full runtime trên Java 25.

### Q: Khác gì `example-embedded-j25` vs `example-quarkus`?

| | embedded-j25 | quarkus |
|---|-------------|---------|
| Entry | `EmbeddedUssdMain.main()` | `UssdDemoBootstrap` + CDI |
| DI | Constructor / static | CDI `@Inject` |
| HTTP RA port | **8082** | **8080** |
| Business flow | **Giống nhau** (3 SBB, 2 RA, 5 events) | **Giống nhau** |

---

## Sơ đồ tổng thể (1 trang)

```
  ussdgw-simulator                grpc-simulator
  (HTTP client)                   (gRPC AS :9090)
       │                                ▲
       │ POST :8080                     │ ResolveMenu
       ▼                                │
┌──────────────────────────────────────────────────────────┐
│  Quarkus process                                         │
│                                                          │
│  UssdDemoBootstrap ──start──► MicroSleeContainer         │
│         │                                                │
│         ├── HttpIngressResourceAdaptor (HTTP RA)         │
│         │        │ beginUssdSession()                    │
│         │        ▼                                       │
│         │   EventRouter ──► HttpServerSbb                │
│         │                      │                         │
│         │                      ▼                         │
│         │                 Ss7UssdIngressSbb (CMP)        │
│         │                      │                         │
│         ├── GrpcMenuResourceAdaptor ◄── requestMenu()    │
│         │        │                                       │
│         │        └──► GrpcClientSbb (child observe)      │
│         │                                                │
│         └── UssdCallbackDispatcher ──POST──► simulator   │
└──────────────────────────────────────────────────────────┘
```

---

*Tài liệu này mô tả code tại branch `micro-jainslee` v1.1.0 — kiến trúc RA-based USSD demo. Cập nhật: 2026-06-28.*
