# App Guide: example-quarkus-helloworld-web (HelloWorld Web trên Quarkus)

> Hướng dẫn chi tiết app HelloWorld Web — HTTP ingress qua ra-http-server + JAIN SLEE SBB pipeline trên Quarkus 3.
> Tham khảo: `docs/vi/app-guide.md` (wiring pattern), `vendor-ras/ra-http-server/` (RA implementation).
> Last updated: 2026-07-15

---

## 1. Ví dụ này làm gì

Đây là một web app "Hello World" chạy trên Quarkus 3 + micro-jainslee, **tuân thủ nghiêm ngặt SLEE 1.1**.

**Kiến trúc SLEE-compliant:** TẤT CẢ HTTP traffic đều đi qua `ra-http-server` (port 8081). Không có REST endpoint riêng, không dùng trực tiếp Vert.x trong app code.

**Flow:**
```
Browser → ra-http-server (port 8081) → HttpWebRequestEvent
  → EventRouter (Disruptor) → HelloWorldSbb.onEvent()
  → HttpResponseCommand → ra-http-server → HTTP response
```

Quarkus HTTP port 8080 chỉ phục vụ static HTML UI từ `META-INF/resources/`.

---

## 2. Cấu trúc thư mục

```
example/example-quarkus-helloworld-web/
├── pom.xml
├── GUIDE.md
├── src/main/resources/
│   ├── application.properties
│   ├── log4j2.xml
│   └── META-INF/resources/
│       └── index.html
└── src/main/java/com/example/helloworld/quarkus/
    ├── bootstrap/
    │   └── HelloWorldBootstrap.java    ← @ApplicationScoped CDI bean
    └── sbbs/
        └── HelloWorldSbb.java          ← SBB xử lý HttpWebRequestEvent
```

**Chỉ 2 file Java** — bootstrap + SBB. Không có events, commands, REST, telemetry, autonomous riêng vì:
- **Events**: Dùng `HttpWebRequestEvent` từ `ra-http-server` (không định nghĩa lại)
- **Commands**: Dùng `HttpResponseCommand` từ `ra-http-server` (không định nghĩa lại)
- **REST**: Không có — tất cả HTTP qua ra-http-server
- **Telemetry/Autonomous/AI**: Đã bỏ để tuân thủ SLEE

---

## 3. pom.xml

```xml
<dependencies>
    <!-- Quarkus CDI only (no REST, no Undertow) -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-arc</artifactId>
    </dependency>

    <!-- micro-jainslee core + API + APT -->
    <dependency>
        <groupId>com.microjainslee</groupId>
        <artifactId>jainslee-core</artifactId>
        <version>1.2.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>com.microjainslee</groupId>
        <artifactId>jainslee-api</artifactId>
        <version>1.2.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>com.microjainslee</groupId>
        <artifactId>jainslee-apt</artifactId>
        <version>1.2.0-SNAPSHOT</version>
        <optional>true</optional>
    </dependency>

    <!-- ra-http-server: HTTP ingress (Vert.x), fires HttpWebRequestEvent -->
    <dependency>
        <groupId>com.microjainslee</groupId>
        <artifactId>ra-http-server</artifactId>
        <version>1.2.0-SNAPSHOT</version>
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-junit5</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>io.rest-assured</groupId>
        <artifactId>rest-assured</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### application.properties

```properties
# Quarkus HTTP — serves static web UI
quarkus.http.port=8080

# ra-http-server port — JAIN SLEE event ingress
http.ra.port=8081

# micro-jainslee core config
microjainslee.buffer-size=4096
microjainslee.prefer-virtual-threads=true
microjainslee.sbb-pool-min=16
microjainslee.sbb-pool-max=10000
```

---

## 4. HelloWorldBootstrap.java

`@ApplicationScoped` CDI bean với `@PostConstruct` / `@PreDestroy` lifecycle.
Wires ra-http-server + HelloWorldSbb vào MicroSleeContainer.

```java
@ApplicationScoped
public final class HelloWorldBootstrap {

    @Inject MicroSleeContainer container;

    @ConfigProperty(name = "http.ra.port", defaultValue = "8081")
    int httpRaPort;

    private volatile HttpServerRaEndpoint httpEndpoint;

    @PostConstruct
    void init() {
        // 1. Start container (if not already started)
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }

        // 2. Register SBB type
        container.registerSbbType(HelloWorldSbb.class,
                () -> new HelloWorldSbb(container));
        container.createIesDispatcher();

        // 3. Map event → SBB
        container.mapEventToSbb(HttpWebRequestEvent.class, "HelloWorldSbb");

        // 4. Wire ra-http-server
        wireHttpRa();
    }

    private void wireHttpRa() {
        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setPort(httpRaPort);
        httpEndpoint = new HttpServerRaEndpoint(ra);
        httpEndpoint.setPort(httpRaPort);
        container.registerRa(httpEndpoint, httpEndpoint);
    }

    @PreDestroy
    void shutdown() {
        if (httpEndpoint != null) {
            httpEndpoint.deactivate();
        }
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }
}
```

**Thứ tự wiring:**
1. `container.start()` — khởi động Disruptor event bus
2. `container.registerSbbType()` — đăng ký SBB class + factory
3. `container.createIesDispatcher()` — tạo Initial Event Selector dispatcher
4. `container.mapEventToSbb()` — map event type → SBB name
5. `container.registerRa()` — đăng ký RA endpoint (vừa là RaEndpointPort vừa là RaCommandPort)

---

## 5. HelloWorldSbb.java

SBB nhận `HttpWebRequestEvent` từ ra-http-server, tạo JSON response, gửi lại qua `HttpResponseCommand`.

```java
public final class HelloWorldSbb implements Sbb, SleeEventHandler {

    @InjectRa(name = "http-server-ra")
    private volatile RaCommandPort httpCommandPort;

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof HttpWebRequestEvent req) {
            onWebRequest(req);
        }
    }

    private void onWebRequest(HttpWebRequestEvent event) {
        String userAgent = event.getUserAgent() != null
                ? event.getUserAgent() : "unknown";

        String body = "{\"message\":\"Hello World\",\"userAgent\":\""
                + userAgent + "\"}";

        httpCommandPort.sendCommand(new HttpServerCommand.HttpResponseCommand(
                event.getSessionId(), 200, "application/json", body));
    }
}
```

**Key patterns:**
- `@InjectRa(name = "http-server-ra")` — inject RA command port, phải match với `HttpServerRaEndpoint.getRaName()`
- `event instanceof HttpWebRequestEvent req` — pattern matching (Java 25)
- `httpCommandPort.sendCommand(HttpResponseCommand)` — gửi response qua RA, KHÔNG dùng trực tiếp Vert.x

---

## 6. Build & Run

### Cài đặt dependencies

```bash
cd /path/to/jain-slee
JAVA_HOME=/path/to/zulu-25 mvn install -pl jainslee-api,jainslee-core,jainslee-ra-spi,vendor-ras/ra-http-server -DskipTests -q
```

### Compile

```bash
JAVA_HOME=/path/to/zulu-25 mvn compile -f example/example-quarkus-helloworld-web/pom.xml
```

### Run

```bash
JAVA_HOME=/path/to/zulu-25 mvn quarkus:dev -f example/example-quarkus-helloworld-web/pom.xml
```

### Test

```bash
# Static UI (Quarkus port 8080)
curl http://localhost:8080/

# SLEE event pipeline (ra-http-server port 8081)
curl http://localhost:8081/hello
# → {"message":"Hello World","userAgent":"curl/8.x.x"}

curl -H "User-Agent: MyBrowser/1.0" http://localhost:8081/api/test
# → {"message":"Hello World","userAgent":"MyBrowser/1.0"}
```

---

## 7. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  Quarkus Application                                        │
│                                                             │
│  port 8080 ─── Static HTML UI (META-INF/resources/)        │
│                                                             │
│  port 8081 ─── ra-http-server (Vert.x HTTP server)          │
│                   │                                         │
│                   ▼ HttpWebRequestEvent                     │
│              EventRouter (Disruptor)                        │
│                   │                                         │
│                   ▼                                         │
│              HelloWorldSbb.onEvent()                        │
│                   │                                         │
│                   ▼ HttpResponseCommand                     │
│              ra-http-server ─── HTTP response               │
│                                                             │
│  No REST endpoints. No direct Vert.x in app code.           │
│  Strict JAIN SLEE 1.1 compliance.                           │
└─────────────────────────────────────────────────────────────┘
```

---

## 8. So sánh với phiên bản cũ (vi phạm SLEE)

| Khía cạnh | Phiên bản cũ (WRONG) | Phiên bản mới (CORRECT) |
|---|---|---|
| HTTP ingress | `@Path("/health")` REST endpoint | ra-http-server (port 8081) |
| Event flow | SBB gọi trực tiếp Vert.x Router | Event → Disruptor → SBB → Command |
| Telemetry | HTTP server riêng port 8090 | Đã bỏ (SLEE-compliant) |
| Autonomous | Mount routes trên Vert.x Router | Đã bỏ (SLEE-compliant) |
| Events | App tự định nghĩa HttpWebRequestEvent | Dùng event từ ra-http-server |
| Commands | App tự định nghĩa HelloWorldCommand | Dùng command từ ra-http-server |
| File count | 10+ file Java | Chỉ 2 file (bootstrap + SBB) |
