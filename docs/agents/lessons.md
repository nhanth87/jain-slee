# Lessons learned — do not repeat (micro-jainslee)

Short memory for **runtime / RA** footguns shared with Digicom hosts. App-product detail (USSD NI park, OTA CAP saga, admin HTML) stays in the consumer trees — link out, do not paste wholesale.

**Shared merge:** workspace [`docs/agents/lessons.md`](../../../docs/agents/lessons.md) · skill `digicom-et-host`.

Peer hosts: ussdgw [`docs/agents/lessons.md`](../../../../worktrees/ussd-service/ussd-microjainslee/docs/agents/lessons.md) · OTA [`docs/agents/lessons.md`](../../../../worktrees/ota-service/ota-sim-push/docs/agents/lessons.md).

## Do not

| Mistake | Rule | Detail |
|---------|------|--------|
| `HttpCallbackCommand.JsonPostRequest` **3-arg** when the body is not JSON | Compact ctor hardcodes **`Content-Type: application/json`**. XML (or any non-JSON) POST needs the **4-arg** ctor with explicit `contentType`. | `vendor-ras/ra-http-client` · `HttpCallbackCommand` |
| Verifying SCTP with **netstat** (or “empty netstat ⇒ down”) | Use `ss -ln --sctp` + `/proc/net/sctp/{eps,assocs}`. Empty netstat is **not** proof SCTP is down. | LINK STATUS § · OTA/ussdgw ss7-lab-pair |
| Leaving **`sbb-pool-max=4096`** (runtime default) as the **10k TPS** app target | Defaults stay conservative. Digicom **10k** hosts raise pool ×10 (`sbb-pool-max=40960`, bump `buffer-size` / `sbb-pool-min` as needed). Quarkus knobs are often **BUILD_TIME** — re-package the host. | `MicroSleeConfiguration` · `MicroJainsleeBuildConfig` · ussdgw lessons |
| Bare **virtual-thread** work with no try/catch (or ignoring pin) | VT are first-class on **Java 25** — always catch + log on worker paths; use pin diagnostics when chasing carrier stalls. Do not add reflection shims “for older JVMs”. | root [AGENTS.md](../../AGENTS.md) · `VirtualThreadSbbEntityPool` |
| Cursor / agent injects **`Co-authored-by: Cursor`** (or other AI trailers) | Authorship **nhanth87 / Tran Nhan** only. Clean message (`commit-tree` if needed); hooks ban AI trailers — never `--no-verify`. | root [AGENTS.md](../../AGENTS.md) § GIT COMMIT AUTHORSHIP |
| Documenting full **USSD classic NI sync park** / AS wire codec here | Runtime hosts the RA; product park/`AdaptiveTimeout` lives in ussdgw. **See ussdgw lessons** (one-liner pointer only). | ussdgw [lessons.md](../../../../worktrees/ussd-service/ussd-microjainslee/docs/agents/lessons.md) |
| Monitor Hub invents its own dark theme / nested `#0c1220` chart wells | Hub must share the **host product** theme key (`ussd-theme` when served by ussdgw; legacy `ota-theme`/`mw-theme` = fallback read only). Brand = host product (Digicom-ET USSDGW), not “micro-jainslee / OTA Admin”. Light remap + surfaces = ink-panel / transparent canvas — never nested pure-black holes. Tailwind opacity utilities need explicit light remaps in product `admin.css`. | `jainslee-monitor` `hub.css`/`index.html`/`hub.js` · ussdgw skills § Admin · OTA [admin-ui.md](../../../../worktrees/ota-service/ota-sim-push/docs/agents/admin-ui.md) |
| Trusting host logs alone when the peer is **HTTPS / TLS** | Prove the **artifact on the wire** (TLS/SNI). Empty 200 body can still be HTTP “OK” and fail product semantics. | Digicom grill 2026-08-08 · ussdgw lessons |
| Stack/RA listening only primary SSN while peer uses another | `services` → TCAP **`setExtraSsns`** for every non-primary SSN. Digicom MO peer Called SSN **147** (gsmSCF) needs boot log `Registered SCCP listener with extra ssn 147`. Wrong SSN/port/ALPN → UDTS / silent drop. | coral-valley `Ss7ConfigLoader.extraSsns` · `TCAPProviderImpl` |
| Deploy that **overwrites** Digicom/prod `configs/` or skips package | Digicom hosts are **prod-bound** (PostgreSQL, live peers) — not disposable. Package before ship; rsync jars/`lib`/UI only; never clobber `configs/`; `db-kind` build-time; **Java 25**. | root AGENTS § DIST · ussdgw/OTA packaging |
| Async JDBC flusher dropping entity columns | INSERT must include every column the entity sets — schema migration alone does not persist fields (ussdgw CDR `gate_ms` sibling). | ussdgw `CdrDbFlusher` |
| Tenant / network id **≠** SCCP / routing partition | Routing plane key must match (USSD `network_id` ≡ SCCP `networkId`). | ussdgw lessons |

## Remember

- Link UP = peer plane truth (`isM3uaRouteReady` / bind / CER), never LISTEN / `isActive()` alone — root AGENTS § LINK STATUS.
- Ship Digicom apps as **`dist/`** directory trees (UI files under `html/` / `app/html/`) — never WAR for lab/prod. Root AGENTS § DIST.
- Digicom ussdgw = **prod-bound** (PostgreSQL DB `ussdgw`, Balance Plus) — never treat as wipe-friendly toy lab; never overwrite Digicom `configs/` on rsync.
- Dated lab notes **2026-08-07**: 4-arg `JsonPostRequest`, SCTP via `ss`/`/proc`, 10k pool target **40960**, VT discipline, attribution hooks.
- Dated **2026-08-08** (cross-cutting): wire proof (TLS/SNI); bind peer’s real SSN via `extraSsns`; dist honesty / no config clobber; JDBC flusher column completeness; network/tenant scoping.

## Synced 2026-08-23 — framework changes proven on gmlc-microjainslee (Monitor Hub branding / pack discovery / KPI)

- **Hub branding is per-app now**: `META-INF/resources/index.html` carries an `@@APP_NAME@@` token; `MonitorHandler` gained a 5-arg ctor `(telemetry, healthJson, ai, registry, appName)` replacing it at serve-time. Resolution order: ctor arg → system property `microjainslee.monitor.app-name` → legacy default `Digicom-ET USSDGW`. Apps pass their product name (GMLC: `gmlc.admin.monitor-app-name`, default `Digicom-ET GMLC`) or every non-USSDGW product shows USSDGW branding. Installed in local 1.2.0-SNAPSHOT 2026-08-23.
- **`AdminDashboardRegistry.load()` cannot see services inside the consumer's ROOT app jar** under Quarkus fast-jar layering — ServiceLoader over TCCL finds only packs in `lib/main` jars. Apps must build the registry explicitly and append their own contributor (merge TCCL + SPI CL + app CL, dedupe by `raName`); reference impl: gmlc `AdminHttpHandler.buildHub()`. Document this in the SPI javadoc when touching admin-spi next.
- **`RaCollector.updateState(raName, state, port)` is still an unfed seam** — no RA calls it, so telemetry snapshot shows `state=UNKNOWN, port=0` until an RA/app publishes state. If you wire RAs to push state, mirror it into the KPI panel too.
- **Protocol-KPI pattern lives app-side** (reference: gmlc `GmlcKpi` + `GmlcKpiContributor`): LongAdder map as source of truth + passive Micrometer mirrors via `TelemetryPort.customCounter` (`gmlc_kpi_*`) + an own `RaAdminDashboardContributor` tab polling `/api/ra/{ra}/status.html`. Framework provides the seams only.

## Synced 2026-08-24 — ADR 0004 P0 hardening shipped in runtime (consumer trees must adapt habits)

Implemented in this repo (runtime modules), full regression 1065→1099 tests green (same single pre-existing SIP BYE-defer failure). Consumer trees pick these up on next runtime jar refresh:

- **`shadow-profile-accessor.sh` is OBSOLETE** once a host consumes runtime jars built ≥ 2026-08-24: the split-package `ProfileAccessorInvoker` stub no longer exists — api ships a delegating facade over `ProfileAccessorBridge` (`META-INF/services`, provided by core; container also installs explicitly). Keep the script harmless until upgrade, then delete it. New failure signature when runtime missing: `IllegalStateException("No ProfileAccessorBridge installed …")` — grep for this, not UOE.
- **Boot now FAILS FAST on `@InjectRa` typos** (`RA wiring validation failed …` listing `Class#field → raName`). This replaces silent null ports (classic mistake #4). Ordering-safe: with zero RAs registered the check defers and reports at injection time instead (S5 flow unaffected). Escape hatch `-Djainslee.inject-ra.validation=warn|off`. If a consumer app fails boot with this message → fix the RA name, do not disable validation casually.
- **RA state telemetry is container-fed** (`RaObserver.onStateChange` → `RaCollector`): the 2026-08-23 row above about the "unfed seam" is CLOSED for apps installing `TelemetryRaObserver`; `/metrics` shows real ACTIVE/ERROR/STOPPING/INACTIVE.
- **Stray profile-facility binding warns loudly**: constructing a second `InMemoryProfileFacility` over a live global logs WARN naming the previous owner; debug aid `ProfileFieldStoreLocator.globalOwner()` (the ussdTx re-bind workaround class).

## Synced 2026-08-26 — Nextgen STP P1 (headless-UI test caught what 28 unit tests could not)

Proven on `worktrees/Nextgen STP/stp-microjainslee` (Quarkus fast-jar + ra-jss7 j25). Five real defects surfaced only by driving the admin UI with a headless browser — unit tests + curl (API-key auth skips CSRF) were all green:

- **Every server-rendered POST form needs its own hidden `_csrf`** — `stp-shell.js` only fills inputs that exist in the DOM; a form missing the hidden field 403s with "Session expired" while curl with `X-STP-Admin-Key` succeeds (API-key principals skip CSRF). Corollary: **headless-browser UI testing is mandatory** before claiming admin features work; curl evidence cannot catch this class.
- **`jboss-logmanager` is a structural jar in Quarkus fast-jar** — `QuarkusEntryPoint` unconditionally `setProperty(java.util.logging.manager=org.jboss.logmanager.LogManager)`; excluding the jar kills boot (`NoClassDefFoundError`) and `quarkus-junit` drags it into test scope (surefire fork dies). Policy answer: keep the jar silent, route every facade INTO Log4j2 (`log4j-slf4j2-impl` single slf4j provider, `log4j-jul` + preset JUL manager in surefire/run.sh, `log4j-1.2-api`, `-Dorg.jboss.logging.provider=log4j2`), ban all other backends via enforcer + a source-scan test. Drop `quarkus-junit` when no `@QuarkusTest` exists.
- **jSS7 SCTP state persists to `<stack>-sctp_sctp.xml` and reloads on every start** — servers/associations from deleted connections keep binding their ports forever across re-applies (stale 8025/8027 endpoints). Fix: orphan sweep in `Ss7StackBuilder.initSctp()` — after adding current links, drop any server/association not in the current config.
- **jSS7 GTT rule JSON with `to={pc,ssn}` (no gt)**: `Ss7ConfigLoader.normAddr` defaults gt to `*`; the builder must synthesize a `-`-per-section GlobalTitle with `RI=DPC_AND_SSN` (translationPrimary) or `RouterExtImpl.addRule` section validation fails and translate() emits GT-routed garbage. Mask sections must equal pattern sections.
- **Monitor Hub pack tab stuck at "loading…"** = missing `META-INF/resources/jainslee-admin/<raName>/panel.html` classpath resource — the API (`/api/ra/<ra>/status`) works, the static panel 404s. Ship the panel resource with the app jar.
- **`setsid` background JVMs vs tool-timeout kills**: the killed shell's group may miss the setsid child (zombie survives) or the child may die with the group (nohup notwithstanding). Before booting: `pgrep -af "quarkus-run.jar" | grep -v "bash -c"` and kill each PID; find which PID owns a stale SCTP port by walking `/proc/<pid>/fd` inodes against `/proc/net/sctp/eps`.
- **Per-tree `mise.toml` (`java = "zulu-25"`)** + `mise trust`; `resolve_java25` must verify the mise-resolved JDK is actually 25 (`mise where java` happily returns zulu-8 when no pin exists).
