# Lessons applied from sip-freeswitch / Elisa

Backported into `ra-sip-servlet` (micro-jainslee):

| Lesson | RA change |
|--------|-----------|
| SIP only, not SBC | Documented; no RTP sockets; ICE/STUN gather only |
| 3GPP P-headers for 5G/6G | `ImsSipHeaderNames`, `SipInviteEvent.imsHeaders`, `SendInvite` whitelist forward |
| rtp_redirect / firewall media | `SipRaConfig` TURN + `preferRelayCandidate`; ICE sort relay first |
| SIP transaction SM | Defer `endDialog` until final response to inbound BYE |
| Hot-path CPS | Keep DEBUG first-line only (already clean) |

App policy (488 without TURN, FS `bypass_media=false`) stays in Elisa — not in the RA.
