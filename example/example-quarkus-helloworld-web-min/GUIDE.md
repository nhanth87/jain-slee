# App Guide: example-quarkus-helloworld-web-min (HelloWorld Web MIN)

> **Minimum template** — chỉ bootstrap + SBB, không telemetry, không autonomous, không AI.
> Dùng làm template khởi đầu cho mọi JAIN SLEE app trên Quarkus 3.
> Last updated: 2026-07-15

---

## 1. Ví dụ này là gì

Đây là phiên bản **tối thiểu nhất** của HelloWorld Web app. Chỉ có 2 file Java:
- `HelloWorldBootstrap.java` — wire ra-http-server + SBB vào container
- `HelloWorldSbb.java` — xử lý HTTP request, trả về JSON "Hello World"

**Không có:** telemetry, autonomous, AI agent, REST endpoints, custom events, custom commands.

**Flow:**
```
HTTP request → ra-http-server (port 8081) → HttpWebRequestEvent
  → EventRouter (Disruptor) → HelloWorldSbb.onEvent()
  → HttpResponseCommand → ra-http-server → HTTP response
```

---

## 2. Cấu trúc thư mục

```
example/example-quarkus-helloworld-web-min/
├── pom.xml
├── GUIDE.md
├── src/main/resources/
│   ├── application.properties
│   ├── log4j2.xml
│   └── META-INF/resources/
│       └── index.html
└── src/main/java/com/example/helloworld/min/quarkus/
    ├── bootstrap/
    │   └── HelloWorldBootstrap.java
    └── sbbs/
        └── HelloWorldSbb.java
```

**Chỉ 2 file Java.** Dùng events và commands từ `ra-http-server`.

---

## 3. pom.xml

```xml
<dependencies>
    <!-- Quarkus CDI only -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-arc</artifactId>
    </dependency>

    <!-- micro-jainslee -->
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

    <!-- ra-http-server -->
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

---

## 4. HelloWorldBootstrap.java

```java
@ApplicationScoped
public final class HelloWorldBootstrap {

    @Inject MicroSleeContainer container;

    @ConfigProperty(name = "http.ra.port", defaultValue = "8081")
    int httpRaPort;

    private volatile HttpServerRaEndpoint httpEndpoint;

    @PostConstruct
    void init() {
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }

        container.registerSbbType(HelloWorldSbb.class,
                () -> new HelloWorldSbb(container));
        container.createIesDispatcher();
        container.mapEventToSbb(HttpWebRequestEvent.class, "HelloWorldSbb");

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
        if (httpEndpoint != null) httpEndpoint.deactivate();
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }
}
```

---

## 5. HelloWorldSbb.java

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

---

## 6. Build & Run

### Cài dependencies

```bash
cd /path/to/jain-slee
JAVA_HOME=/path/to/zulu-25 mvn install \
  -pl jainslee-api,jainslee-core,jainslee-ra-spi,vendor-ras/ra-http-server \
  -DskipTests -q
```

### Compile

```bash
JAVA_HOME=/path/to/zulu-25 mvn compile \
  -f example/example-quarkus-helloworld-web-min/pom.xml
```

### Run

```bash
JAVA_HOME=/path/to/zulu-25 mvn quarkus:dev \
  -f example/example-quarkus-helloworld-web-min/pom.xml
```

### Test

```bash
# SLEE event pipeline
curl http://localhost:8081/hello
# → {"message":"Hello World","userAgent":"curl/8.x.x"}
```

---

## 7. Dùng làm template cho app mới

Để tạo app mới từ template này:

```bash
cp -r example/example-quarkus-helloworld-web-min example/example-quarkus-myapp
```

Sau đó:
1. Sửa `pom.xml`: artifactId, name, description
2. Đổi package `com.example.helloworld.min.quarkus` → package của bạn
3. Đổi tên class: `HelloWorldBootstrap` → `MyAppBootstrap`, `HelloWorldSbb` → `MyAppSbb`
4. Thêm business logic vào SBB
5. Nếu cần thêm RA (vd: jSS7, Kafka), thêm dependency + wire trong bootstrap

---

## 8. Khác biệt với bản đầy đủ

| Khía cạnh | Bản MIN | Bản đầy đủ |
|---|---|---|
| File Java | 2 (bootstrap + SBB) | 2 (giống hệt) |
| Telemetry | Không | Không |
| Autonomous | Không | Không |
| AI Agent | Không | Không |
| REST endpoints | Không | Không |
| Package | `com.example.helloworld.min.quarkus` | `com.example.helloworld.quarkus` |
| artifactId | `example-quarkus-helloworld-web-min` | `example-quarkus-helloworld-web` |

> Cả 2 bản đều **SLEE-compliant**. Bản MIN là template khởi đầu, bản đầy đủ là reference implementation.
