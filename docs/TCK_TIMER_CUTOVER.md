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
