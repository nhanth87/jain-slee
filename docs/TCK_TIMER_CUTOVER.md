# JAIN-SLEE Timer Backend Cutover — TCK Gate

**Target:** Replace or supplement `FaultTolerantScheduler` with `SleeTimerSchedulerBridge` / jSS7 `TimerScheduler`  
**Version:** micro-jainslee 1.1.0  
**Status:** Bridge implemented; **TCK not run** (external suite required)

---

## TCK location

The OpenCloud JAIN SLEE 1.1 TCK is **not** in this repository.

| Item | Value |
|------|-------|
| Source | [RestComm/jain-slee.more](https://github.com/RestComm/jain-slee.more) |
| Path | `tck/jain-slee-1.1/` |
| Test runner | Ant (`testsuite.zip`), not Maven surefire |
| WildFly module | `release/opencloud/` → `$JBOSS_HOME/modules/.../com/opencloud/` |

---

## Prerequisites

1. Build container: `cd jain-slee/jain-slee && mvn install`
2. Build TCK: `cd $TCK_HOME && mvn install`
3. Copy OpenCloud module to WildFly
4. Start WildFly with Security Manager (`-secmgr`) and TCK policy file

```bash
export TCK_HOME=/path/to/jain-slee.more/tck/jain-slee-1.1
export JAVA_OPTS="-Djboss.defined.home=$JBOSS_HOME \
  -Djava.security.manager=default \
  -Djava.security.policy=file://$TCK_HOME/tck-security-wildfly.policy"

cd $TCK_HOME && unzip -o testsuite.zip -d testsuite
cd testsuite && ant
```

Timer subset (after inspecting TCK `build.xml`):

```bash
ant -Dtest.includes='**/facilities/timer/**'
```

---

## Cutover checklist

### Before enabling bridge

- [ ] Feature flag in `TimerFacilityImpl` — default remains `FaultTolerantScheduler`
- [ ] `SleeTimerSchedulerBridge` fires via `EventRouter`, never on timer thread
- [ ] JTA: `SetTimerAfterTxCommitRunnable` / `CancelTimerAfterTxCommitRunnable` preserved
- [ ] Separate Infinispan container (`microjainslee`) — do not share `jss7-timers`

### TCK gate (mandatory)

- [ ] Full `ant` suite — **zero failures**
- [ ] Timer facility package green (`com.opencloud.sleetck.lib.testsuite.facilities.timer.*`)
- [ ] Set + rollback → timer does not fire
- [ ] Cancel + rollback → timer still fires
- [ ] Set + cancel same transaction → timer never fires
- [ ] `TimerPreserveMissed`: NONE, ALL, LAST
- [ ] Periodic: finite, infinite (`numRepetitions=0`), past `startTime` catch-up
- [ ] `getActivityContextInterface(TimerID)` requires active transaction
- [ ] Timer events delivered on Event Router thread

### HA (Phase 3 — optional before first cutover)

- [ ] Cluster failover with persistent periodic timers
- [ ] Callback rehydration on survivor node

### Rollback

Restore `FaultTolerantScheduler` in `TimerFacilityImpl.sleeStarting()` and redeploy.

---

## In-repo references

| Class | Role |
|-------|------|
| `TimerFacilityImpl` | Production timer façade |
| `FaultTolerantScheduler` | Current backend |
| `TimerFacilityTimerTask` | Fire → `ActivityContext.fireEvent()` |
| `SleeTimerSchedulerBridge` | micro-jainslee bridge (jainslee-core) |
| `TimerFacilityBackendBridge` | Mobicents adapter sketch (container/timers) |

---

## micro-jainslee status (1.1.0)

- `HierarchicalTimingWheel` removed
- `TimerPortImpl` + `SleeTimerSchedulerBridge` + `LocalTimerAdapter` (jSS7 9.4.0)
- **Not wired into Mobicents `TimerFacilityImpl`** — TCK applies to full container cutover only

---

## Perfect Core S1–S5 status (2026-06-28)

**TCK infrastructure is in place** — module `jainslee-tck-harness` ships
with the Perfect Core S5 deliverable. It contains:

- TCK test class skeletons cho JAIN SLEE 1.1 §6.5 (CMP facility) và §8 (event routing / initial event selector).
- Custom `TestRunner` với `MicroSleeContainer` in-process — không cần WildFly / Security Manager để chạy happy-path assertions.
- ~15 baseline TCK-style tests, all-green.

**Tuy nhiên, full TCK run vẫn deferred** vì:

| Lý do | Chi tiết |
|---|---|
| OpenCloud TCK suite requires Ant + WildFly | Test runner ngoài kia là Ant (`testsuite.zip`), không phải Maven surefire — integrate đầy đủ tốn effort lớn ngoài scope Perfect Core. |
| Security Manager policy | OpenCloud TCK yêu cầu `-secmgr` + custom policy file. JDK 25 LTS đã deprecate Security Manager — phải workaround hoặc dùng JDK 21 riêng cho CI. |
| External test fixtures | Một số test cần JNDI, JTA real driver, JMX remote port — embed platform cần thêm bootstrap. |

**Status tóm tắt:**

| Module | Test count (Perfect Core) | TCK-equivalent | Note |
|--------|------:|---|---|
| `jainslee-core` (CMP) | ~30 (CMP) + ~150 total | §6.5 partial | Reflection runtime + InMemoryCmpFieldStore + CmpTransactionBridge (S2) |
| `jainslee-core` (IES) | ~22 | §8 partial | `@InitialEventSelect` + `InitialEventSelectorDispatcher` (S5) |
| `jainslee-core` (timer) | ~25 | Timer facility partial | `TimerPortImpl` + `SleeTimerSchedulerBridge` |
| `jainslee-codegen` | ~18 | §6.5.2 accessor | `ConcreteSbbGenerator` (S2) |
| `jainslee-tck-harness` | ~15 | TCK skeleton | R&D harness — **NOT** full OpenCloud TCK |

**Next steps (deferred — không nằm trong Perfect Core S1–S5):**

1. **S6 (future):** Container cutover — wire `TimerPortImpl` vào Mobicents `TimerFacilityImpl` thật (chỉ khi có nhu cầu production cutover).
2. **S7 (future):** Full OpenCloud TCK port — convert Ant testsuite sang Maven surefire + JUnit 5, ship trong `jainslee-tck-harness` với profile `tck.opencloud`.
3. **Production migration:** Khi cần, dùng `jainslee-tck-harness` làm regression baseline trước khi cutover sang jSS7 `SleeTimerSchedulerBridge` trên Mobicents cluster.

Cho R&D / lab: `jainslee-tck-harness` đã đủ để verify micro-jainslee không vi phạm spec trên những test chính (CMP, IES, timer). Full TCK gate vẫn nên chạy trên WildFly + OpenCloud testsuite trước khi mang vào production USSD.
