# micro-jainslee CMP (Container-Managed Persistence) — Production Roadmap

> **Audience:** engineers maintain micro-jainslee và cần đưa CMP implementation từ R&D → production carrier-grade.
>
> **Status:** ⚠️ **CMP đã có baseline (annotation + reflection + InMemoryCmpFieldStore + 15 tests pass) NHƯNG còn ~10 gaps quan trọng để production-ready.**
>
> **Last updated:** 2026-06-28 · **Maintainer:** Tran Nhan (nhanth87@gmail.com)
>
> **Perfect Core S1–S5 update (2026-06-28):** module `jainslee-codegen` đã ship — `ConcreteSbbGenerator` + Javassist deploy-time codegen (GAP-CMP-2 ✅ SKELETON DONE, runtime wiring pending). Module `jainslee-tx` với `CmpTransactionBridge` snapshot/rollback (GAP-CMP-1 cũng tiến bộ). Chi tiết xem §1.2 và GAP-CMP-2 dưới đây.
>
> **Cross-reference roadmap này với Perfect Core:** xem §6 cuối file cho mapping đầy đủ S1–S5 → GAP-CMP, plus P2 (cluster) + P5 (CascadeRemover) status.

---

## 1. Tổng quan — Spec JAIN SLEE 1.1 §6.5 vs micro-jainslee hiện tại

### 1.1 Spec JAIN SLEE 1.1 §6.5 — yêu cầu chính

| Spec rule | Mô tả |
|---|---|
| **§6.5.1** CMP field naming | Field name là Java identifier; exposed qua abstract `getXxx()` / `setXxx()` method (JavaBeans naming convention). Optional `isXxx()` cho boolean. |
| **§6.5.2** Accessor methods must be abstract | Container cung cấp implementation lúc deploy time (hoặc runtime qua reflection). |
| **§6.5.3** Default values | Nếu chưa set, primitive = zero/false, Object = `null`. |
| **§6.5.4** Indexed fields | Field được đánh `indexed="true"` phải được index trong store nếu store hỗ trợ. |
| **§6.5.5** Passivation | Trước khi SBB ngưng dùng (passivate → pool), container gọi `sbbStore()` rồi lưu state. |
| **§6.5.6** Activation | Khi SBB được activate lại, container gọi `sbbLoad()` để restore state. |
| **§6.5.7** Transactional rollback | CMP writes trong transaction rollback → state phải restore về snapshot trước khi sbbCreate(). |
| **§6.5.8** Field types | Phải là: primitive, `String`, `Serializable`, hoặc `Collection`/`Map` của các kiểu trên. |
| **§6.5.9** Schema evolution | Field có thể add/remove giữa version SBB — container phải xử lý backward-compat. |
| **§6.5.10** Concurrency | 1 SBB entity chỉ chạy trên 1 thread tại 1 thời điểm — không có concurrent CMP writes. |

### 1.2 micro-jainslee hiện tại (verified 2026-06-28, refreshed after Perfect Core S1–S5)

| Spec rule | Status | Implementation |
|---|---|---|
| §6.5.1 JavaBeans naming | ✅ DONE | `CmpAccessorInvoker.fieldNameFor(Method)` — strip `get`/`set`/`is`, lower-case first char |
| §6.5.2 Abstract accessor | ✅ **SKELETON DONE** (Perfect Core S2) | `jainslee-codegen` module + `ConcreteSbbGenerator` (Javassist). Reflection runtime vẫn là fallback khi `codegenEnabled=false`. **Runtime wiring từ container → concrete class đang làm tiếp** — chi tiết GAP-CMP-2 dưới. |
| §6.5.3 Default values | ✅ DONE | `CmpAccessorInvoker.defaultForType(Class)` — primitives zero, Object null |
| §6.5.4 Indexed fields | ⚠️ PARTIAL | `@CmpField.indexed()` field exists nhưng không có store nào enforce indexing |
| §6.5.5 Passivation | ✅ DONE | `SbbLifecycleManager.passivate()` gọi `sbb.sbbStore()` |
| §6.5.6 Activation | ✅ DONE | `SbbLifecycleManager.activate()` gọi `sbb.sbbLoad()` |
| §6.5.7 Transactional rollback | 🟡 **IN PROGRESS** (Perfect Core S2) | Module `jainslee-tx` có `CmpTransactionBridge` + snapshot/restore interface — **đã có skeleton + ThreadLocal stack + 8 tests**. Wire-in vào `EventRouter.dispatchWithTransaction` đang làm tiếp (xem GAP-CMP-1). |
| §6.5.8 Field types | ⚠️ PARTIAL | `defensivelyCopyIfSerializable` check Serializable, nhưng không reject invalid types |
| §6.5.9 Schema evolution | ❌ MISSING | Không có version migration logic |
| §6.5.10 Concurrency | ✅ DONE | Mỗi SBB entity pinned trên 1 virtual thread (`VirtualThreadSbbEntityPool`) |

**Perfect Core S1–S5 module additions affecting CMP:**

| Module mới | File | Note |
|---|---|---|
| `jainslee-codegen` | `ConcreteSbbGenerator.java`, `JavassistDeployTimeCodegen.java` | Deploy-time Javassist codegen, ~600 LOC main + ~18 tests. |
| `jainslee-tx` | `CmpTransactionBridge.java` | ThreadLocal snapshot stack, ~250 LOC main + ~8 tests. |
| `jainslee-tck-harness` | `*` | TCK skeleton cho §6.5/§8 — ~15 tests, R&D scope. |

**Files hiện có** (verified):
```
jainslee-api/src/main/java/com/microjainslee/api/annotations/CmpField.java
jainslee-api/src/main/java/com/microjainslee/api/ProfileAbstractCmp.java
jainslee-core/src/main/java/com/microjainslee/core/CmpBackedSbb.java
jainslee-core/src/main/java/com/microjainslee/core/CmpFieldStore.java
jainslee-core/src/main/java/com/microjainslee/core/CmpFieldStoreLocator.java
jainslee-core/src/main/java/com/microjainslee/core/CmpAccessorInvoker.java
jainslee-core/src/main/java/com/microjainslee/core/InMemoryCmpFieldStore.java
jainslee-core/src/test/java/com/microjainslee/core/CmpFieldStoreTest.java        (5 tests)
jainslee-core/src/test/java/com/microjainslee/core/CmpEndToEndTest.java           (3 tests)
jainslee-core/src/test/java/com/microjainslee/core/CmpAdvancedEdgeTest.java       (7 tests)
```

**Test baseline** (2026-06-28): 15 CMP tests pass trong 246-test reactor (256 sau P1).

### 1.3 So sánh với Mobicents SLEE reference implementation

Mobicents `container/profiles/` (~14 KLOC Java) implement đầy đủ:
- `ProfileCMPFieldProxyGenerator` (Javassist deploy-time codegen)
- `ConcreteSbb` (synthetic class implement abstract accessors)
- `ProfileCMPFieldIndexManager` (indexed field hash lookup)
- `CMPFieldBridge` (transactional snapshot/rollback với JTA)
- `ConcreteObjectPool` (concrete class caching)

micro-jainslee hiện dùng reflection runtime thay vì codegen — đơn giản hơn nhưng chậm hơn ~10x trên hot path (theo audit `optimizejainsleep2.md`).

---

## 2. 10 Production Gaps — implementation plan

Các gap được sắp xếp theo **độ quan trọng (impact)** và **độ phức tạp (effort)**. Mỗi gap có:
- Mã gap ID (GAP-CMP-N)
- Spec reference
- Hiện trạng
- File cần tạo/sửa
- Acceptance criteria
- Effort estimate

### GAP-CMP-1: Transactional rollback (spec §6.5.7) — 🔴 P0

**Hiện trạng:** `InMemoryCmpFieldStore` không có snapshot/rollback. Khi transaction rollback, CMP writes đã commit không thể undo.

**File mới**: `jainslee-core/src/main/java/com/microjainslee/core/CmpTransactionBridge.java`
- Interface với `Map<String, Object> snapshot(String entityId)`, `void restore(String entityId, Map<String, Object> snapshot)`, `void discard(String entityId)`.
- Sử dụng ThreadLocal stack: mỗi transaction push snapshot, commit pop+discard, rollback pop+restore.

**Wire-in**:
- `EventRouter.dispatchWithTransaction` (đã có sẵn JTA integration P1) — wrap `deliverEvent` với `cmpBridge.snapshot()` ở start, `cmpBridge.restore()` nếu rollback, `cmpBridge.discard()` nếu commit.
- `MicroSleeContainer.start()` — install `CmpTransactionBridge` instance, bind vào `CmpFieldStoreLocator`.

**Tests** (3-5):
- Rollback restores all CMP fields to snapshot.
- Commit discards snapshot.
- Nested rollback (2 levels) restores only outer snapshot.
- Snapshot isolation: 2 concurrent SBB entities don't see each other's snapshot.

**Effort**: 1 sprint (2 tuần). LOC ~300 main + 150 test.

---

### GAP-CMP-2: Concrete SBB codegen với Javassist (spec §6.5.2) — 🔴 P0

> **Perfect Core S2 status (2026-06-28):** ⚠️ PARTIAL → ✅ **SKELETON DONE**.
>
> - Module `jainslee-codegen` đã ship với `ConcreteSbbGenerator` + `JavassistDeployTimeCodegen` (~600 LOC main, ~18 tests all-green).
> - Reflection runtime vẫn là fallback khi `MicroSleeConfiguration.codegenEnabled=false`.
> - **Còn lại (runtime wiring):** `MicroSleeContainer.start()` tự động scan `@CmpField`-annotated SBB classes, gọi `ConcreteSbbGenerator` cho mỗi class, replace pool factory instance bằng concrete class. Hiện đang implement tiếp (1–2 sprints).

**Hiện trạng (trước Perfect Core S2):** `CmpBackedSbb.cmpRead/cmpWrite` dùng reflection Method.invoke() — chậm. Production USSD scale ~5.000 TPS cần direct method dispatch.

**Hiện trạng (sau Perfect Core S2):** Module `jainslee-codegen` đã có skeleton + deploy-time codegen. Tests verify rằng concrete class loads được, methods có cùng observable behavior với reflection. **Còn thiếu**: container tự động pick concrete class khi acquire SBB entity.

**File đã có** (Perfect Core S2):
- `jainslee-codegen/src/main/java/com/microjainslee/codegen/ConcreteSbbGenerator.java` (~600 LOC) ✅
- `jainslee-codegen/src/main/java/com/microjainslee/codegen/JavassistDeployTimeCodegen.java` ✅
- `jainslee-codegen/src/main/java/com/microjainslee/codegen/CmpAccessorImpl.java` ✅

**Còn phải làm (runtime wiring)**:
- File sửa: `jainslee-core/src/main/java/com/microjainslee/core/MicroSleeContainer.java` — khi `start()` và `MicroSleeConfiguration.codegenEnabled=true`, scan `@CmpField` annotations trên mọi `registerSbb(...)` call, generate concrete class, instantiate thay cho abstract class.
- File sửa: `jainslee-core/src/main/java/com/microjainslee/core/VirtualThreadSbbEntityPool.java` — pool factory nhận concrete `Class<?>` thay vì abstract `Class<? extends Sbb>`.
- File mới: `jainslee-codegen/src/main/java/com/microjainslee/codegen/ContainerIntegration.java` (~150 LOC) — wrapper gọi generator từ container startup path.

**Tests** (existing 18 + new 5):
- ✅ Existing: Generated concrete class has same observable behavior as reflection version.
- ✅ Existing: 100K setter/getter cycles benchmark: reflection ≥ 50ms, generated ≤ 5ms (10× faster).
- ✅ Existing: Generated class loads qua custom ClassLoader.
- ✅ Existing: Failure modes: invalid accessor name, non-abstract method, primitive wrapper mismatch.
- 🆕 Pending: Container pick concrete class automatically at startup.
- 🆕 Pending: Pool factory uses concrete class — verify `acquireEntity` returns concrete instance.
- 🆕 Pending: Fallback to reflection when `codegenEnabled=false` — both paths produce identical observable behavior.

**Effort**: 2 sprints (1 tháng). LOC ~700 main + 250 test. (Hiện ~620 LOC main + ~18 tests done; còn ~80 LOC runtime wiring + ~5 tests.)

---

### GAP-CMP-3: Field type validation (spec §6.5.8) — 🟡 P1

**Hiện trạng:** `InMemoryCmpFieldStore` chỉ check `Serializable` qua `defensivelyCopyIfSerializable()`. Không reject các kiểu invalid (e.g., `Object` non-Serializable, raw `Thread`, `Socket`).

**File sửa**: `jainslee-core/src/main/java/com/microjainslee/core/CmpAccessorInvoker.java`
- Thêm method `validateFieldType(Class<?> javaType)` — check:
  - primitive (8 types) → OK
  - `String` → OK
  - `Serializable` → OK
  - `Collection` với generic param OK → recursive check
  - `Map` với generic OK → recursive check
  - else throw `InvalidCmpFieldTypeException`

**File mới**: `jainslee-core/src/main/java/com/microjainslee/core/InvalidCmpFieldTypeException.java` (~30 LOC).

**Tests** (5+):
- Primitive types pass.
- String, Integer, Long, Boolean pass.
- `java.util.Date` pass (Serializable).
- `ArrayList<String>` pass.
- `HashMap<String, Integer>` pass.
- `Thread`, `Socket`, `Object` rejected.
- `ArrayList<Thread>` rejected.

**Effort**: 0.5 sprint (1 tuần). LOC ~150 main + 200 test.

---

### GAP-CMP-4: Indexed fields lookup (spec §6.5.4) — 🟡 P1

**Hiện trạng:** `@CmpField.indexed()` field exists nhưng không có store nào query qua index.

**File sửa**: `jainslee-core/src/main/java/com/microjainslee/core/InMemoryCmpFieldStore.java`
- Thêm secondary `ConcurrentMap<String, ConcurrentMap<Object, Set<String>>> indexes` — key = indexed field name, value = (value → entityIds).
- `store()` — update indexes khi indexed field thay đổi.
- Thêm method `Set<String> findByIndex(String fieldName, Object value)`.

**File mới**: `jainslee-core/src/main/java/com/microjainslee/core/CmpIndex.java`
- Index registry scan-on-demand qua reflection trên `@CmpField` annotations.
- Cache scan results per SBB class (lazy).

**Tests** (3-5):
- Insert 100 entities với indexed field.
- findByIndex returns correct subset.
- Update indexed field → index maintained.
- Multiple indexed fields per entity.

**Effort**: 1 sprint. LOC ~300 main + 200 test.

---

### GAP-CMP-5: Passivation timeout (spec §6.5.5) — 🟡 P1

**Hiện trạng:** `SbbLifecycleManager.passivate()` không có timeout. Nếu `sbb.sbbStore()` block vô hạn → container treo.

**File sửa**: `jainslee-core/src/main/java/com/microjainslee/core/SbbLifecycleManager.java`
- Add `MicroSleeConfiguration.passivationTimeoutMs` (default 5000).
- `passivate()` wrap `sbb.sbbStore()` trong `Future` + `Future.get(timeout, MILLISECONDS)`.
- Nếu timeout → log warning + force complete passivate (mark entity POOLED without full store).
- Reentrant lock: dùng `ReentrantLock` thay `synchronized`.

**Tests** (3-5):
- sbbStore returns trong timeout OK.
- sbbStore block > timeout → timeout exception → entity POOLED anyway.
- sbbStore throws → catch + entity POOLED.
- Timeout config respected từ `MicroSleeConfiguration`.

**Effort**: 0.5 sprint. LOC ~150 main + 200 test.

---

### GAP-CMP-6: Schema evolution / version migration (spec §6.5.9) — 🟡 P1

**Hiện trạng:** Không có version tracking. Nếu SBB thêm field mới → field đó default = 0/null cho entity cũ (silent). Nếu SBB xoá field → field cũ vẫn còn trong store (waste).

**File mới**: `jainslee-core/src/main/java/com/microjainslee/core/CmpSchemaVersion.java`
- Track version per entity: `Map<String, Integer> entityId → schemaVersion`.
- `InMemoryCmpFieldStore.migrate(entityId, Class<? extends Sbb> sbbClass, int oldVersion, int newVersion)`.
- SchemaVersion được tính từ sorted set of `@CmpField` names + types (hash).

**Tests** (5+):
- Add field to SBB → migrate existing entity, new field defaults correctly.
- Remove field → migrate drops old field, persists only new.
- Change field type → reject (incompatible).
- No change → no migration overhead.

**Effort**: 1 sprint. LOC ~250 main + 250 test.

---

### GAP-CMP-7: CMP distributed snapshot integration với P2 — 🔴 P0

**Hiện trạng:** `DistributedSbbEntityPool.takeSnapshot()` (P2.3 commit `8d89bdfd5`) chỉ là stub. Production USSD cần CMP fields được replicate cluster-wide khi SBB passivated/migrated.

**File sửa**: `jainslee-cluster/src/main/java/com/microjainslee/cluster/DistributedSbbEntityPool.java` (P2.3)
- `takeSnapshot()` gọi `CmpFieldStore.load(entityId)` → đưa vào snapshot.
- `applySnapshot()` restore fields qua `CmpFieldStore.store(entityId, ...)`.
- Snapshot phải bao gồm cả SBB state + CMP fields.

**File mới**: `jainslee-cluster/src/main/java/com/microjainslee/cluster/CmpClusterSync.java` (~200 LOC)
- Khi node khởi động → fetch snapshots từ cluster cache.
- Khi passivate → push snapshot to cluster cache REPL_SYNC.
- Conflict resolution: nếu 2 node cùng modify → last-write-wins + log warning.

**Tests** (3-5):
- Snapshot trên node1, recover trên node2 → CMP fields match.
- Update CMP sau khi acquire → snapshot có giá trị mới.
- Network partition → no corruption (last-write-wins).

**Effort**: 1 sprint. LOC ~250 main + 200 test.

---

### GAP-CMP-8: CMP write batching (performance) — 🟢 P2

**Hiện trạng:** Mỗi `cmpWrite` → 1 store call. SBB với 50 CMP fields × 10 events = 500 store calls/event. Waste.

**File sửa**: `jainslee-core/src/main/java/com/microjainslee/core/InMemoryCmpFieldStore.java`
- `store(entityId, state)` giờ là `commitTransaction()` thay vì direct write.
- `setValue()` accumulate vào buffer local.
- Container gọi `flush()` ở cuối `sbbStore()` hoặc commit transaction.
- Buffer dùng `Map<String, Map<String, Object>>` — outer key = entityId.

**Tests** (3-5):
- 100 setter calls → chỉ 1 flush.
- Flush sau khi transaction rollback → buffer cleared, no writes.
- Multiple entities trong cùng transaction → flush tất cả.

**Effort**: 0.5 sprint. LOC ~150 main + 150 test.

---

### GAP-CMP-9: CMP audit log + observability — 🟢 P2

**Hiện trạng:** Không có log cho CMP read/write — debug production issue rất khó.

**File sửa**: `jainslee-core/src/main/java/com/microjainslee/core/CmpAccessorInvoker.java`
- Thêm log4j2 logger `CmpAudit`.
- Log ở level DEBUG: `entity=X field=Y op=read/write value=Z`.
- Log ở level TRACE: full state dump (chỉ khi enable).

**File mới**: `jainslee-core/src/main/java/com/microjainslee/core/CmpAuditLogger.java`
- Singleton MDC-aware logger với entity/field context.
- Integration với EventMdc từ P1.3 (existing `core/logging/EventMdc.java`).

**Tests** (2-3):
- Logger invoked với đúng entity/field/value.
- MDC fields propagated từ EventMdc.

**Effort**: 0.5 sprint. LOC ~150 main + 100 test.

---

### GAP-CMP-10: CMP field migration test suite — 🟢 P2

**Hiện trạng:** 15 CMP tests pass nhưng chỉ test happy path + 1-2 edge cases. Production cần test schema migration, concurrent access, JTA integration.

**File mới**: `jainslee-core/src/test/java/com/microjainslee/core/CmpProductionStressTest.java` (~500 LOC)
- 100K entities với 10 fields mỗi entity.
- 1M read/write cycles đo throughput.
- JTA integration: 1000 transactions với rollback verification.
- Schema migration: v1 → v2 với field add/remove.
- Cluster integration: snapshot + restore across 2-node.

**Tests** (10+):
- 100K entity creation < 5 sec.
- 1M read < 1 sec.
- 1M write < 2 sec.
- 1000 transactions với 50% rollback < 10 sec.
- Migration correctness for 5 schema versions.

**Effort**: 1 sprint. LOC ~500 test.

---

## 3. Implementation roadmap — 10 sprints

| Sprint | GAP | LOC estimate | Effort |
|---|---|---:|---:|
| S1 (2 tuần) | GAP-CMP-3 (validate type) + GAP-CMP-5 (passivation timeout) | 600 | 1 tháng |
| S2 (2 tuần) | GAP-CMP-1 (transactional rollback) | 450 | 1 tháng |
| S3 (3 tuần) | GAP-CMP-2 (Javassist codegen) | 950 | 1.5 tháng |
| S4 (2 tuần) | GAP-CMP-4 (indexed fields) | 500 | 1 tháng |
| S5 (2 tuần) | GAP-CMP-6 (schema evolution) | 500 | 1 tháng |
| S6 (2 tuần) | GAP-CMP-7 (cluster snapshot integration) | 450 | 1 tháng |
| S7 (1 tuần) | GAP-CMP-8 (write batching) + GAP-CMP-9 (audit log) | 550 | 0.5 tháng |
| S8 (2 tuần) | GAP-CMP-10 (production stress test) | 500 | 1 tháng |
| **Total** | **10 gaps** | **~4.500 LOC** | **~8 tháng** |

## 4. Priority cho team hiện tại

**Nếu 1-2 engineers, budget < $200K:**
- ✅ Skip — CMP đã có baseline (15 tests), dùng được cho R&D/lab.
- Chỉ làm GAP-CMP-1 (transactional rollback) + GAP-CMP-3 (validate type) — 2 sprints quan trọng nhất.

**Nếu 3-4 engineers, budget $500K+:**
- Làm tất cả GAP-CMP-1..6 (cluster + JTA integration đầy đủ).
- Skip GAP-CMP-2 (codegen) — reflection đủ nhanh cho ~1K TPS.
- Skip GAP-CMP-8..10 — performance/observability là polish.

**Nếu full team (4+ engineers, $800K+):**
- Full plan 8 sprints.
- Đặc biệt GAP-CMP-2 (Javassist codegen) sẽ cho 10× speedup trên hot path USSD scale.

## 5. Acceptance criteria cho CMP production-ready

- [ ] Tất cả 10 gaps resolved, merged vào `micro-jainslee` branch
- [ ] Test count: 50+ CMP tests (từ 15 baseline)
- [ ] JAIN SLEE 1.1 §6.5 TCK group: 100% pass
- [ ] Throughput: ≥ 1M CMP read/sec single-thread, ≥ 500K write/sec
- [ ] Rollback latency: < 10ms p99 cho 50-field entity
- [ ] Snapshot/restore cluster: < 100ms p99 cho 50-field entity
- [ ] Javadoc đầy đủ cho tất cả public API
- [ ] Production runbook section "CMP migration" + "CMP rollback recovery"

---

## 6. Perfect Core S1–S5 — what shipped (cross-reference, 2026-06-28)

> Roadmap này được viết cho CMP gap-track, nhưng Perfect Core S1–S5 đã deliver nhiều building block mà các gaps ở trên dựa vào. Section này map từng sprint ra file/commit cụ thể — thay thế cho các "tier1/tier2 roadmap" riêng không còn tồn tại trong repo.

### 6.1 Phase mapping (S1–S5 → GAP-CMP)

| Sprint | What shipped | File / commit | GAP affected |
|---|---|---|---|
| **S1 JTA wiring** | `jainslee-tx` module với Narayana JTA integration (`SleeTransactionManager`, `CmpTransactionBridge` skeleton, ThreadLocal snapshot stack, 8 tests) | `ae3666a89 feat(tx): add Narayana JTA integration in jainslee-tx module` | **GAP-CMP-1** (transactional rollback) — còn phải wire `EventRouter.dispatchWithTransaction` |
| **S2 CMP codegen** | `jainslee-codegen` module — `ConcreteSbbGenerator` + `JavassistDeployTimeCodegen` + `CmpAccessorImpl`; `VirtualThreadSbbEntityPool.acquireByClass()` reflectively routes qua codegen; `MicroSleeConfiguration.codegenEnabled` (default true) | `a7566ed29 feat(perfect-core): S2 — Javassist CMP codegen for concrete SBB classes` | **GAP-CMP-2** ✅ **SKELETON DONE** — còn runtime wiring container-side |
| **S3 IES** | `jainslee-core/ies/` package — `InitialEventSelectCondition`, `InitialEventSelectResult`, `InitialEventSelectorDispatcher`; `@InitialEventSelect` annotation; `EventRouter.bindInitialEventSelectorDispatcher()` + `routeIncomingEvent()` | `37c7e4c36 feat(perfect-core): S3 — Initial Event Selector wiring for SBB convergence` | Indirectly unblocks GAP-CMP-2 wiring (entity reuse trước khi codegen pickup) |
| **S4 Child** | `jainslee-core/child/` package — `CascadeRemover` (depth-first, instance-based), `ChildRelationImpl<T>` (ConcurrentHashMap-backed), `ChildRelationFactory`; `VirtualThreadSbbEntityPool.createChildRelation()` + `asEntityLookup()`; `SimpleSbbLocalObject` get/set parent | `05cefe3dc feat(perfect-core): S4 — ChildRelation impl + CascadeRemover wiring` | **GAP-CMP-2 partial** — CascadeRemover is depth-first (covers most of GAP-CMP-2 cascade removal semantics); codegen + cascade full integration là task còn lại |
| **S5 RA wiring** | Full RA wiring với `RaEntityStateMachine` (INACTIVE/ACTIVE/STOPPING), `SleeEndpointImpl` (full spec surface), `ResourceAdaptorContextImpl` + `ResourceAdaptorContextBuilder`; `MicroSleeContainer.registerResourceAdaptor()` + `stopRA()`; 35 new tests | `a2029f26d feat(perfect-core): S5 — full RA wiring with state machine + endpoint + context` | Indirectly enables GAP-CMP-7 cluster snapshot (RA SBB entities giờ có state machine) |

### 6.2 P2 (cluster layer) — implemented ✅

> **Status (2026-06-28):** Cluster layer + `DistributedSBBPool` + `ClusteredACNF` đã implemented. (Refs: commits `8496efd47` + `8d89bdfd5`.)

| Commit | What | Files |
|---|---|---|
| `8496efd47 feat(cluster): add ClusteredActivityContextNamingFacility with Infinispan DIST_SYNC` | Infinispan-backed `ClusteredActivityContextNamingFacility` + `ClusterManager` (282 LOC main) | `jainslee-cluster/pom.xml`, `ClusterManager.java` (282 LOC), `ClusteredActivityContextNamingFacility.java` (202 LOC), `ClusterManagerTest.java` (327 LOC) |
| `8d89bdfd5 feat(cluster): add DistributedSbbEntityPool with state snapshot/replication` | `DistributedSbbEntityPool` với `SbbEntitySnapshot` — SBB passivation/migration support | `jainslee-cluster/src/main/java/com/microjainslee/cluster/DistributedSbbEntityPool.java`, `SbbEntitySnapshot.java` |

**Implication cho roadmap này:** GAP-CMP-7 ("CMP distributed snapshot integration với P2") có infrastructure xong; chỉ còn wire `CmpFieldStore.load/store` vào `DistributedSbbEntityPool.takeSnapshot/applySnapshot` (~250 LOC + 200 test, xem GAP-CMP-7 §2 ở trên).

### 6.3 P5 (CascadeRemover depth-first) — done ✅ (partial GAP-CMP-2 via codegen)

> **Status (2026-06-28):** `CascadeRemover` depth-first đã ship như một phần của Perfect Core S4. Đồng thời **GAP-CMP-2 partial** (codegen module) đã ship như một phần của Perfect Core S2.

| Artifact | Where | Spec section covered |
|---|---|---|
| `CascadeRemover.java` (depth-first, instance-based) | `jainslee-core/src/main/java/com/microjainslee/core/child/CascadeRemover.java` | JAIN SLEE 1.1 §6.4.4 (ChildRelation removal cascade) |
| `ChildRelationImpl<T>` | `jainslee-core/src/main/java/com/microjainslee/core/child/ChildRelationImpl.java` | §6.4.2 — `getChildRelation()` returns child SBB entities |
| `ChildRelationFactory` | `jainslee-core/src/main/java/com/microjainslee/core/child/ChildRelationFactory.java` | Reflection-based discovery of abstract `ChildRelation<T>` methods |
| `VirtualThreadSbbEntityPool.createChildRelation()` + `asEntityLookup()` | Updated in S4 | Cascade removal drives pool lifecycle |

**Còn lại để full GAP-CMP-2 close:**
- Container pick concrete class automatically khi `registerSbb()` được gọi với `codegenEnabled=true` (xem GAP-CMP-2 §2 "Còn phải làm" ở trên).
- Pool factory uses concrete class — `acquireEntity` returns concrete instance.
- Fallback reflection khi `codegenEnabled=false` phải produce identical observable behavior.

### 6.4 Module inventory sau Perfect Core (14 modules)

```
micro-jainslee (parent pom)
├── micro-jainslee-bom                          (BOM — Narayana/Infinispan/JGroups/Javassist versions)
├── jainslee-api                                (SBB, SleeEvent, ResourceAdaptor, annotations, @InitialEventSelect — S3)
├── jainslee-scheduler                          (HashedWheelTimer — vendored jSS7)
├── jainslee-core                               (MicroSleeContainer, EventRouter, VirtualThreadSbbEntityPool, SbbLifecycleManager, CmpBackedSbb, ies/, child/, ra/)
│   ├── core/ies/                               (InitialEventSelectorDispatcher, etc. — S3 NEW)
│   ├── core/child/                             (CascadeRemover, ChildRelationImpl — S4 NEW)
│   ├── core/ra/                                (ResourceAdaptorContextBuilder — S5 NEW)
│   └── core/logging/                           (EventMdc — pre-S1)
├── jainslee-codegen                            (NEW — ConcreteSbbGenerator, JavassistDeployTimeCodegen — S2)
├── jainslee-tx                                 (NEW — SleeTransactionManager, Narayana integration — S1)
├── jainslee-apt                                (Annotation processor)
├── jainslee-ra-spi                             (AbstractResourceAdaptor, RaEntityStateMachine, SleeEndpointImpl, ResourceAdaptorContextImpl — S5 heavily updated)
├── jainslee-cluster                            (NEW — ClusteredACNF, DistributedSbbEntityPool, ClusterManager — P2)
├── jainslee-tck-harness                        (NEW — TCK skeleton §6.5/§8)
├── adapter-quarkus                             (Quarkus 3.15.1 extension)
├── adapter-springboot                          (Spring Boot 3 auto-config)
├── adapter-jakartaee                           (Jakarta EE 9 EJB)
└── example/example-quarkus                     (Full USSD gateway demo)
```

### 6.5 Test count delta

| Phase | Test count | Notes |
|---|---:|---|
| Pre-Perfect-Core baseline | 246 | reactor-wide |
| + S1 (JTA) | 246 + 8 (jainslee-tx) = **254** | JTA TransactionManagerTest + bridge |
| + S2 (Codegen) | 254 + 17 = **271** | ConcreteSbbGeneratorTest (8) + JavassistDeployTimeCodegenTest (9) |
| + S3 (IES) | 271 + 27 = **298** | IES result builder, dispatcher, EventRouter wiring, MicroSleeContainer rebind |
| + S4 (Child) | 298 + 37 = **335** | ChildRelationImplTest (18) + ChildRelationFactoryTest (14) + CascadeRemoverEndToEndTest (5) |
| + S5 (RA) | 335 + 35 = **370** | RaEntityStateMachineTest (12) + SleeEndpointImplTest (13) + ResourceAdaptorContextImplTest (10) |
| Final reactor (Jun 2026) | **~423** | includes cluster + tck-harness tests as well |

> All test numbers measured via `mvn test` against branch `micro-jainslee` HEAD `7cc31d06b`.

