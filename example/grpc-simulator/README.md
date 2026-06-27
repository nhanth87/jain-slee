# grpc-simulator

Standalone gRPC USSD Application Server implementing `UssdMenuService.ResolveMenu`.
No micro-jainslee dependency — runs independently on port **9090**.

---

## English — Run

**Prerequisites:** JDK 21+, Maven 3.9+.

```bash
cd example/grpc-simulator
mvn -B -ntp package

# Default port 9090
java -cp target/grpc-simulator.jar:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
  com.example.grpcsimulator.GrpcSimulatorMain

# Custom port
java -cp target/grpc-simulator.jar:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
  com.example.grpcsimulator.GrpcSimulatorMain 9091
```

**Menu rules:**

| USSD string | Response |
|-------------|----------|
| `*123#` | Balance menu (contains `Balance`) |
| unknown | `ERR` |

Start this **before** any example app when running the full 5-project demo.

---

## English — Run tests

```bash
cd example/grpc-simulator
mvn -B -ntp test
```

| Test | What it verifies |
|------|------------------|
| ResolveMenu happy path | `*123#` returns balance menu |
| Unknown short code | Returns error |
| Empty session-id | Rejected gracefully |
| Server shutdown | Clean stop |

Expected: **4 tests, 0 failures**.

---

## Tiếng Việt — Chạy thử

**Yêu cầu:** JDK 21+, Maven 3.9+.

```bash
cd example/grpc-simulator
mvn -B -ntp package

# Port mặc định 9090
java -cp target/grpc-simulator.jar:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
  com.example.grpcsimulator.GrpcSimulatorMain
```

Đây là backend menu bên ngoài — các example app (8080/8081/8082) gọi tới `127.0.0.1:9090`.
Khởi động grpc-simulator **trước** khi chạy demo đầy đủ 5 project.

---

## Tiếng Việt — Chạy test

```bash
cd example/grpc-simulator
mvn -B -ntp test
```

Kiểm tra RPC `ResolveMenu`, mã USSD không hợp lệ, session rỗng, và shutdown sạch.

Kỳ vọng: **4 test, 0 lỗi**.
