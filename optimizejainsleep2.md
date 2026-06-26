# JAIN-SLEE Phase 2 — Giải pháp A: Deploy-time Javassist Codegen

Mục tiêu: **không sinh bytecode lúc runtime** trên hot path Disruptor. Mọi concrete class (SBB, ACI, local object, profile…) được **ghi `.class` xuống deployment dir** lúc deploy service, JVM chỉ `ClassLoader.loadClass()`.

## Trạng thái codebase (RestComm jain-slee v8)

| Kiểm tra | Kết quả |
|----------|---------|
| `CtClass.toClass()` | **Không có** trong toàn repo |
| Runtime `ProxyFactory` | **Không có** — doc cũ mô tả sai |
| Codegen hiện tại | Deploy-time qua `writeFile(deployDir)` |
| API tập trung | `JavassistDeployTimeCodegen` (common module) |

## API chính

```java
// Ghi bytecode — không gọi toClass()
JavassistDeployTimeCodegen.persist(ctClass, deployDir);

// Ghi + load từ deployment ClassLoader
Class<?> clazz = JavassistDeployTimeCodegen.persistAndLoad(ctClass, deployDir);
```

**Override thư mục output (Maven/build):**

```bash
-Djainslee.codegen.output.dir=./target/generated-classes
```

## Generator đã chuyển sang API mới

- `ConcreteSbbGenerator` — concrete SBB impl
- `ConcreteSbbLocalObjectGenerator` — SBB local object impl
- `ConcreteActivityContextInterfaceGenerator` — ACI wrapper
- `SbbAbstractClassDecorator` — decorated abstract SBB (spec traps)

Các generator profile/usage vẫn gọi `writeFile()` trực tiếp (đã đúng Solution A); có thể migrate dần sang `JavassistDeployTimeCodegen`.

## Quy trình deploy service (runtime)

```
Service DU deploy
  → SbbClassCodeGenerator.process()
  → ConcreteSbbGenerator.generateConcreteSbb()
  → JavassistDeployTimeCodegen.persistAndLoad()
  → *.class trong deploymentDir
  → Component ClassLoader load tĩnh
  → SbbObjectPoolFactory.makeObject() → newInstance() — KHÔNG codegen
```

## Quy trình build (tùy chọn — Spring Boot / fat jar)

1. Chạy deploy codegen offline (TCK deploy hoặc maven plugin tương lai) với `-Djainslee.codegen.output.dir=target/generated-classes`
2. Đóng gói `target/generated-classes/**` vào service jar
3. Runtime: **Javassist không được kích hoạt** trên hot path

## Khác biệt với Giải pháp B

| | Giải pháp A | Giải pháp B |
|---|-------------|-------------|
| Thời điểm | Deploy / build | Startup pool pre-gen |
| Mục tiêu | Zero runtime bytecode gen | Zero per-event alloc (pool/proxy) |
| Trạng thái | **Đã implement** | Chưa — xem Phase 2 plan (ACI reuse, MethodHandle) |

## Verify

```bash
cd jain-slee/container/common
mvn test -Dtest=JavassistDeployTimeCodegenTest
```

Audit không còn `toClass(`:

```bash
rg '\.toClass\(' jain-slee --glob '*.java'
```

---

# Phase 3 — Micro JAIN-SLEE Container & Spring Boot Integration

Mục tiêu: Loại bỏ hoàn toàn sự phụ thuộc vào JBoss/Wildfly (modules, VFS, JMX, Deployers), đóng gói JAIN-SLEE thành một container siêu nhẹ (micro-container) có thể nhúng trực tiếp vào Spring Boot. Tận dụng tối đa các tính năng của Java 25 (Virtual Threads, ZGC) để tối ưu hiệu năng theo chuẩn JAIN-SLEE 1.1.

## Các giai đoạn triển khai (Phases of Implementation)

### Phase 3.1: Decoupling (Tách JBoss khỏi Core)
- **Classloading:** Thay thế JBoss VFS (Virtual File System) và JBoss Modules bằng Standard Java Classloader. Các DU (Deployable Unit) sẽ được load từ classpath của Spring Boot hoặc custom URLClassLoader.
- **Management & JMX:** Gỡ bỏ JBoss JMX/MSC/Subsystem. Chuyển đổi `SleeManagementMBean` và các MBean khác sang Standard `javax.management` hoặc Spring JMX.
- **Clustering & Cache:** Tách Infinispan khỏi JBoss AS subsystem. Cấu hình Infinispan embedded độc lập hoặc thay thế bằng Spring Cache abstraction.
- **Transaction:** Loại bỏ JBoss Transactions, chuyển sang sử dụng Spring JTA (Narayana embedded hoặc Atomikos) để quản lý SLEE transactions.

### Phase 3.2: Spring Boot Integration (Nhúng vào Spring)
- **Spring Boot Starter:** Tạo module `jain-slee-spring-boot-starter`.
- **Lifecycle Mapping:** Map các lifecycle của SLEE (`SleeContainer.initSlee()`, `start()`, `stop()`) vào `SmartLifecycle` hoặc `ApplicationRunner` của Spring.
- **Bean Exposure:** Expose các SLEE facilities (EventRouter, TimerFacility, SbbManagement, ActivityContextFactory) thành các Spring Beans (`@Bean`) để các phần khác của hệ thống dễ dàng tương tác.
- **Auto-deployment:** Phát triển cơ chế tự động load các SLEE DU classes đã pre-gen (từ Phase 2 - Solution A) ngay trong quá trình Spring Boot khởi động, bỏ qua quá trình parse XML và tạo bytecode rườm rà.

### Phase 3.3: Java 25 Modernization (Virtual Threads & ZGC)
- **Virtual Threads (Project Loom):**
  - Thay thế các thread pool truyền thống (như trong EventRouter, TimerFacility) bằng Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
  - Lợi ích: SLEE là hệ thống event-driven, việc mỗi event/activity context được xử lý bởi một Virtual Thread sẽ giúp scale lên hàng triệu concurrent calls mà không bị nghẽn OS threads.
- **ZGC (Generational Z Garbage Collector):**
  - Cấu hình JVM arguments để container chạy với ZGC thế hệ mới (`-XX:+UseZGC -XX:+ZGenerational`).
  - Mục tiêu: Sub-millisecond pause times, triệt tiêu độ trễ GC, đáp ứng yêu cầu khắt khe của hệ thống Core Telecom.
- **Locking Optimization:** Rà soát và thay thế các `synchronized` block legacy bằng `ReentrantLock` để tránh hiện tượng Thread Pinning khi dùng Virtual Threads.

### Phase 3.4: Resource Adaptors & External Interfaces
- **Decouple RAs:** Đảm bảo các RA tiêu chuẩn (như Diameter, SIP, gRPC) độc lập hoàn toàn khỏi Wildfly.
- **Non-blocking IO:** Ưu tiên sử dụng Netty làm transport layer cho các RA mới (như đã làm với gRPC RA ở USSD GW), kết hợp với cấu trúc poll-timer hoặc callback tương thích với Virtual Threads.

### Phase 3.5: TCK Verification & Testing
- Chạy lại toàn bộ JAIN-SLEE 1.1 TCK (Technology Compatibility Kit) trên micro-container Spring Boot.
- Khắc phục các vi phạm spec (nếu có) phát sinh do thay đổi cơ chế Classloader và mô hình Threading.
