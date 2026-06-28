## 14. Implementation status — 2026-06-28

Phases A, B, C and **all 5 Perfect Core sprints (S1–S5)** are now implemented
on branch `micro-jainslee`. **Build status: `mvn -pl '!jainslee-cluster'
test` → BUILD SUCCESS.** Cluster tests run separately
(`mvn -pl jainslee-cluster -am test`, ~60 s).

| Phase / Sprint | Coverage | Commit |
|----------------|----------|--------|
| A — CMP + Pooled/Ready state machine | done (10 API files, 8 core files, 4 test files) | pre-S1 |
| B — Child SBB / ChildRelation / cascade remove | done | pre-S1 |
| C — SbbLocalObject spec API (`isIdentical`, `getSbbPriority`, `setSbbPriority`) | done | pre-S1 |
| **S1 — JTA wiring (Narayana 7.0)** | done — `jainslee-tx/JtaTransactionManager` wraps `EventRouter.deliverEvent` | [`ae3666a89`](https://github.com/nhanth87/micro-jainslee/commit/ae3666a89) |
| **S2 — CMP Javassist codegen** | done — `jainslee-codegen/ConcreteSbbGenerator` materializes abstract SBB classes (CMP backing fields + ChildRelation accessors) | [`a7566ed29`](https://github.com/nhanth87/micro-jainslee/commit/a7566ed29) |
| **S3 — IES dispatcher** | done — `core.ies.InitialEventSelectorDispatcher` resolves convergence keys (`msisdn:dialogId`) to the existing SBB entity; fixes broken USSD/SIP-dialog state | [`37c7e4c36`](https://github.com/nhanth87/micro-jainslee/commit/37c7e4c36) |
| **S4 — Child relations + cascade** | done — `ChildRelation<T>` + iterative depth-first post-order `CascadeRemover` | [`05cefe3dc`](https://github.com/nhanth87/micro-jainslee/commit/05cefe3dc) |
| **S5 — RA full wiring** | done — `jainslee-ra-spi/{RaEntityStateMachine, SleeEndpointImpl, ResourceAdaptorContextImpl}` per JAIN SLEE 1.1 §12.4 | [`a2029f26d`](https://github.com/nhanth87/micro-jainslee/commit/a2029f26d) |
| **S6 — TCK harness** | skeleton only — `jainslee-tck-harness/{TckRunner, MicrojainsleeContainerAdapter}`. 5 of 13 JAIN SLEE 1.1 test groups wired | [`0b4210f08`](https://github.com/nhanth87/micro-jainslee/commit/0b4210f08) |

**Still deferred**: Infinispan-backed timer cluster, JSR-77 MBeans,
CMP-style JPA profiles, full TCK coverage, JAIN SLEE 1.1 TCK assertion
run, runtime `CtClass.toClass()` on hot path. See
[`docs/gap-analysis.md`](gap-analysis.md) § DEFERRED for the full list.

**Verification commands**:

```bash
# Perfect Core S1–S5 (skip cluster, ~16 s)
mvn -pl jainslee-api,jainslee-scheduler,jainslee-core,jainslee-tx,jainslee-codegen,jainslee-ra-spi,jainslee-apt -am test

# All-including-cluster (~76 s)
mvn test

# Per-sprint
mvn -pl jainslee-tx         test    # S1
mvn -pl jainslee-codegen    test    # S2
mvn -pl jainslee-core       test -Dtest='InitialEventSelectorDispatcherTest,CascadeRemoverTest,UssdIesEndToEndTest'  # S3+S4
mvn -pl jainslee-ra-spi     test    # S5
```
