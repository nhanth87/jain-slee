# Profile Programming Model

> Engineer-facing guide to using JAIN SLEE §10 Profile as the **hot store** for
> subscriber and network-element state in micro-jainslee.
>
> Last updated: 2026-07-19
>
> Related:
> - [Implementation plan](../../design-ideas/PROFILE-IMPLEMENTATION-PLAN.md) — phased delivery, data-safety contracts C1–C9
> - [Advanced profile design](../../design-ideas/advancedprofile.md) — domain composition, PolyVoice / telecom examples

---

## 1. Profile vs SBB CMP (C9)

| Store | Scope | Lifetime | Use for |
|-------|-------|----------|---------|
| **Profile** | Shared across SBB entities | Survives entity passivate, pool reclaim, JVM restart (with durable backend) | Provisioned user/NE state, billing counters, session checkpoints |
| **SBB CMP** | Entity-local | Dies with the SBB entity | Ephemeral handler state, convergence keys, pointers into Profile |

**Rule:** Recovery data belongs in Profile — especially the Session table checkpoint — not in SBB CMP. SBB CMP may hold `profileKey` (and optional table hints); durable session state is written to Profile on passivate.

Checkpoint write failures must **not** be swallowed (unlike best-effort `cmpPersist`). Log ERROR and raise an alarm — this is user recovery data.

---

## 2. Composition by `profileKey`

Do not model one mega-row per user. Compose **multiple ProfileTables** that share the same `profileKey`:

```
profileKey = "84901234567" | "jwt_sub_…" | "hlr-hn-01"
    ├── SubscriberCore[key]       ← plan, status, timestamps (app-defined CMP)
    ├── SubscriberSession[key]    ← checkpoint JSON, last activity id
    ├── TelecomServing[key]       ← IMSI, serving MSC/HLR ids (optional)
    └── Billing[key]              ← balance, usage counters

profileKey = "msc-sg-07"
    └── MscElement[key]           ← NE profile (separate concern from subscriber)
```

HLR/MSC are **network-element profiles**, not forced slices of every subscriber row. A subscriber profile may reference `currentHlrId` / `currentMscId`; the NE row lives in its own table.

Domain CMP interfaces (`TelecomSubscriberProfile`, `HlrProfile`, …) live in **app or optional kit** — not in `jainslee-api`.

---

## 3. Hot path: no synchronous DB

SBB event handlers and Profile CMP `get`/`set` touch **memory only** (local hot cache). Durability is **write-behind**:

1. Mutate hot store + mark row dirty — return immediately (no IO).
2. Background flusher drains dirty rows in batches (default interval **100 ms** RPO + batch size).
3. Sync flush on shutdown, profile remove, table drop, and configurable passivate hooks.

Writes outside an event delivery (bootstrap, RA thread, management) are **auto-commit** — no undo log.

Hard kill between flushes may lose up to one flush interval of writes unless shutdown flush completed. Document RPO per deployment; Billing-class tables may opt into `flushMode=SYNC` in a later release.

---

## 4. Secondary indexes — explicit registration

Index maintenance is **opt-in per field**. Register before relying on lookup:

```java
facility.registerIndex("TelecomSubscriber", "msisdn");
Collection<ProfileLocalObject> rows =
    facility.findProfilesByAttribute("TelecomSubscriber", "msisdn", "84901234567");
```

**Contract:** `findProfilesByAttribute` on a field that was **not** registered via `registerIndex` throws `IllegalStateException`. There is no silent full-table scan.

Optional `@ProfileIndexed` annotation (APT-generated registration) is deferred; explicit `registerIndex` is the foundation.

Index maps are updated on the same code path as indexed-field `setCmp` writes.

---

## 5. Atomic operations for counters (C4)

Read-modify-write via `get` → compute → `set` races under concurrent handlers on the same profile. **Use facility atomic ops for counters:**

```java
long newBalance = facility.addToLong(profileId, "balanceCents", -priceCents);
Object updated = facility.updateField(profileId, "usageSeconds", v -> ((Long) v) + delta);
boolean ok = facility.compareAndSetField(profileId, "status", "ACTIVE", "SUSPENDED");
```

Every billing/usage mutation in examples and production code must use these ops, not get/set.

---

## 6. Field type whitelist (C7)

Profile field maps store **JDK-safe values only** so rows survive Quarkus live-reload and classloader changes:

| Allowed | Notes |
|---------|-------|
| `String`, boxed primitives, `byte[]` | Direct storage |
| `List` / `Map` / `Set` of the above | Nested collections OK |
| App enums | Store `enum.name()` |
| App POJOs | Serialize to JSON `String` |

`Profile` instances are **ephemeral wrappers** rebuilt from the field map after reload. In dev, `writeField` strict mode (default on) rejects types outside the whitelist with a clear error.

Checkpoint payload: free-form **JSON string** in the Session profile (C7 already allows `String`).

---

## 7. Profile events — opt-in

`ProfileAddedEvent`, `ProfileUpdatedEvent`, and `ProfileRemovedEvent` are **opt-in per table**:

```java
facility.enableEvents("Billing");   // or via config
```

Hot tables that nobody listens to pay no event overhead. Events emit **after commit** of the event delivery (not on rollback). The facility publishes via a coalescing queue + drain thread — mutator threads never block on the Disruptor ring (C5).

---

## 8. SBB recovery — checkpoint + ProfileAttachment

**Convention (v1):**

1. SBB CMP stores `profileKey` (and optional table hints).
2. On **passivate** / pool reclaim: serialize ephemeral entity state to JSON; write via Session table checkpoint field (write-behind flush follows).
3. On **activate** / new entity for same key: `ProfileAttachment` (Phase 3) loads required tables and applies checkpoint — no ad-hoc DB query in the handler.

```java
// Illustrative — ProfileAttachment ships in Phase 3
attachment.checkpoint("SubscriberSession", profileKey, checkpointJson);
Optional<String> json = attachment.restoreCheckpoint("SubscriberSession", profileKey);
```

Live-reload and JVM restart **rehydrate from the durable store** (Infinispan file store, dev = prod). C2 carry-over holder is not used.

Per-delivery **full undo-log** (C3): if the event handler throws after profile writes, `SbbTransactionContext.rollback()` restores previous field values; profile events are not emitted for rolled-back writes.

---

## 9. Quick do / don't

| Do | Don't |
|----|-------|
| Model domain as composed tables sharing one `profileKey` | One shared User table forced on all DUs |
| `registerIndex` before `findProfilesByAttribute` | Assume any CMP field is searchable |
| `addToLong` / `updateField` for counters | `get` → add → `set` on balance/usage |
| Checkpoint session state to Session Profile table | Rely on SBB CMP for crash recovery |
| `enableEvents(table)` only where needed | Enable events on every table by default |
| Store enums as `name()`, POJOs as JSON strings | Store app class instances in field maps |

---

## 10. Implementation status

| Capability | Phase |
|------------|-------|
| Default profile, indexes, atomic ops, type whitelist, opt-in events | 1 |
| Write-behind, Store SPI, shutdown flush (C1), rehydrate (C2) | 2 |
| `ProfileAttachment`, checkpoint helpers, undo log (C3) | 3 |
| Infinispan embedded durable store | 4 |

See [PROFILE-IMPLEMENTATION-PLAN.md](../../design-ideas/PROFILE-IMPLEMENTATION-PLAN.md) for contracts, invariants I1–I6, and test gates.
