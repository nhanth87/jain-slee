# Lessons applied from sip-freeswitch / Elisa

Backported into `ra-sip-servlet` (micro-jainslee):

| Lesson | RA change |
|--------|-----------|
| SIP only, not SBC | Documented; no RTP sockets; ICE/STUN gather only |
| 3GPP P-headers for 5G/6G | `ImsSipHeaderNames`, `SipInviteEvent.imsHeaders`, `SendInvite` whitelist forward |
| rtp_redirect / firewall | `SipRaConfig` TURN + `preferRelayCandidate`; **no fake relay candidates** |
| SIP transaction SM | Defer `endDialog` until final response to inbound BYE |
| Hot-path CPS | DEBUG first-line / ICE select only |

## Grill fixes (2026-08-06)

| Bug | Fix |
|-----|-----|
| `fromUri` was full `From:` header → INVITE to trunk dropped | Extract URI only + `normalizeSipUri` |
| FS 200 stole `dialog.peer` → 200 INVITE sent to FS | Reply peer only updated on requests; `remotePeer` for trunk |
| BYE looped to UA; no 200 | Far-leg BYE via `remotePeer` + ProxySbb `SendBye` then `200` |
| Fake TURN `typ relay` on `:3478` | Removed placeholder ALLOCATE |
| `Proxy-Require` blindly forwarded | Dropped from preserve list |

App policy (488 without TURN, FS `bypass_media=false`) stays in Elisa — not in the RA.
