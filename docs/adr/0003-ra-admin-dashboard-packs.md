# ADR 0003 — RA admin dashboard packs via jainslee-monitor hub

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** micro-jainslee maintainers
- **Related code:** `jainslee-admin-spi`, `jainslee-monitor` (`MonitorHandler`), vendor RA packs (`ra-jss7`, `ra-http-server`), OTA `SmppRaAdminContributor`

## Context

Operators need per-RA admin UIs (SS7 JSON apply, HTTP listen config, SMPP bind status) without growing `jainslee-api` or forking the steampunk monitor into every app. OTA previously owned SS7/SMPP pages under `/admin/ss7` and `/admin/smpp` while CMR/helloworld duplicated `MonitorHandler`.

## Decision

1. **Hub (1A):** Extend `jainslee-monitor` as the single Monitoring Window. Built-in tabs stay Telemetry / Autonomous / AI; RA packs add dynamic tabs. Digicom visual language: ink/signal tokens, DM Sans / JetBrains Mono (match OTA admin — not steampunk).
2. **SPI module:** `jainslee-admin-spi` (`com.microjainslee.admin`) — manifest, contributor, HTTP registrar, `AdminDashboardRegistry` (ServiceLoader), `RaAdminJson` (Jackson). Not in `jainslee-api`.
3. **Fragments, not iframes:** Packs ship `META-INF/resources/jainslee-admin/{raName}/panel.html|js|css`. Hub injects HTML and loads `panel.js` with `data-api-base`.
4. **APIs:** `/api/admin/dashboards` lists manifests; `/admin/ra/{raName}/**` serves static; `/api/ra/{raName}/**` dispatches to the pack.
5. **Link-status truth:** Status JSON must separate local lifecycle (`active` / LISTEN) from peer UP (`routeReady` / `peerReady` / `peerBound` / `anyPeerUp`). Never map `isActive()` alone as live for **peer** protocols.
6. **Rich status + HTMX:** Each pack exposes `GET /api/ra/{ra}/status` (Jackson JSON) and `GET /api/ra/{ra}/status.html` (HTML fragment). Panels poll the HTML fragment with `hx-trigger="load, every 4s"` into `#*-status`. Tab dots still update from JSON. Escape all dynamic HTML text server-side.
7. **Tab light semantics (locked):**
   | Tab | Green | Amber | Never green from |
   |-----|-------|-------|------------------|
   | **SS7** | `routeReady` = `isM3uaRouteReady()` | `active` but not route-ready | LISTEN / `isActive()` alone |
   | **SMPP** | `anyPeerUp` (≥1 bound session or outbound `peerReady`) | LISTEN / client ACTIVE, zero peers | `serverListening` alone |
   | **HTTP Server** | **`listening`** (socket bound) | — | peer fiction (no peer plane) |
8. **HTTP server-RA exception:** For an HTTP *server* RA, “ready for work” = local LISTEN. That is **not** the same as SS7/SMPP peer UP. Documented here so AGENTS.md “LISTEN ≠ green” remains true for peer protocols while the HTTP tab may be green when accepting requests.
9. **Config hygiene:** Status polls must **not** overwrite the config textarea / focused inputs. Reload config after successful validate/apply/rebind. Unbound RA → soft `200` with empty arrays / false flags (never 500 from status).

### Status field checklist

- **SS7:** `active`, `routeReady`, `bound`, `listening`, `peerConnected`, `asActive`, `stackStarted`, `detail`, `servers[]`, `associations[]`, `asps[]`, `applicationServers[]` (parity with OTA `OtaLinkStatusService.ss7Snapshot` via `Ss7LinkStatusSnapshot`).
- **SMPP:** `clients[]`, `server.sessions[]`, `boundSessionCount`, `anyPeerUp`, `detail` (OTA pack).
- **HTTP:** `listening`, `configuredHost`/`configuredPort`, `detail`, `bound`.
- **HTTP endpoints list:** `GET /api/ra/http-server-ra/endpoints` (+ `.html` HTMX fragment). Aggregates `HttpEndpointCatalog` sources: RA listen/`/health`/`/*`, `MonitorHandler` hub paths, app (e.g. OTA) registrations. No peer-UP fields.

## Consequences

- Apps adapt transport → `MonitorHandler.handle(method, path, query, body)` (CMR/helloworld/OTA thin adapters).
- OTA `/admin/ss7` and `/admin/smpp` GET redirect to `/telemetry/?tab=ss7|smpp` (**preserve `?key=`**); campaigns/CDR/tenants stay on OTA nav (link hub tabs for SS7/SMPP/HTTP).
- OTA binds `Ss7AdminBindings.bindHooks` / `SmppAdminBindings.bindHooks` so hub apply persists PG docs and uses plane Apply services; status still reads live RA truth.
- RA jars (or app modules) register `META-INF/services/...RaAdminDashboardContributor`.
- Kill jSS7 sim → SS7 amber/red (`routeReady=false` while LISTEN may remain). ESME unbind → SMPP amber. HTTP listen → green.

## Open hub

- CMR / helloworld: `http://host:port/telemetry/`
- OTA (auth required): `http://host:port/telemetry/?tab=ss7` (after login)
