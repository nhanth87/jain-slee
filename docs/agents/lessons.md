# Lessons learned — do not repeat (micro-jainslee)

Short memory for **runtime / RA** footguns shared with Digicom hosts. App-product detail (USSD NI park, OTA CAP saga, admin HTML) stays in the consumer trees — link out, do not paste wholesale.

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

## Remember

- Link UP = peer plane truth (`isM3uaRouteReady` / bind / CER), never LISTEN / `isActive()` alone — root AGENTS § LINK STATUS.
- Ship Digicom apps as **`dist/`** directory trees (UI files under `html/` / `app/html/`) — never WAR for lab/prod. Root AGENTS § DIST.
- Dated lab notes **2026-08-07**: 4-arg `JsonPostRequest`, SCTP via `ss`/`/proc`, 10k pool target **40960**, VT discipline, attribution hooks.
