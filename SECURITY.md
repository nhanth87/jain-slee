# Security Policy

## Supported versions

micro-jainslee is **R&D-only software**. It is not a production
deployment and does not process untrusted input from the open
internet. Security support is therefore limited to the latest
released minor version, and best-effort only.

| Version | Supported          |
|---------|--------------------|
| 1.1.x   | ✅ best-effort     |
| 1.0.x   | ❌ end-of-life     |
| < 1.0   | ❌ not supported   |

## Reporting a vulnerability

**Do NOT open a public GitHub issue for security vulnerabilities.**
Public issues let attackers find the flaw before a fix is available.

Instead, email **Tran Nhan** at **[nhanth87@gmail.com](mailto:nhanth87@gmail.com)**
with:

1. A short description of the vulnerability.
2. Steps to reproduce (ideally a minimal Java reproducer).
3. The impact you observed or theorised (DoS, RCE, info disclosure, ...).
4. Your name and how you'd like to be credited in the fix's
   `CHANGELOG.md` (optional — anonymous reports are accepted).

You should receive an acknowledgement within **2 business days**.
A patch or workaround timeline is communicated once the maintainer
has reproduced the issue.

## What to expect

Because micro-jainslee is R&D-only, the security response process is
informal but structured:

1. **Triage** within 5 business days — confirm the report, assign a
   severity (Critical / High / Medium / Low), and decide whether to
   patch in-tree or document as a known limitation.
2. **Patch** for High / Critical issues: the maintainer commits a
   fix on a `security/CVE-XXXX-XXXX` branch and requests a CVE via
   GitHub Security Advisories
   (<https://github.com/nhanth87/jain-slee/security/advisories>).
3. **Disclosure**: a GitHub Security Advisory is published, the patch
   lands on `micro-jainslee`, and a `CHANGELOG.md` entry is added with
   the CVE reference.
4. **Credit**: reporters are listed in the advisory's "Credits"
   section unless they prefer to remain anonymous.

## Threat model

What micro-jainslee protects against:

- **Log injection** — all log4j2 messages use parameterized
  placeholders, never string concatenation of user input.
- **Resource exhaustion** — `MicroSleeConfiguration.sbbPoolMax` caps
  the SBB pool size; the EventRouter's LMAX Disruptor ring size is
  configurable.
- **Silent timer leaks** — `SleeTimerSchedulerBridge.shutdown()` calls
  `executor.shutdown()` + `awaitTermination(5s)` before returning;
  `cancelAll(sbb)` is provided for explicit teardown.
- **JNDI injection** in the Jakarta EE adapter — only the four
  hardcoded `JndiNames` constants are bound; user code cannot
  inject arbitrary JNDI names through this adapter.

What micro-jainslee does **not** protect against:

- **Untrusted classloading** — the SBB code runs in the embedding
  application's classloader; the embedding app is responsible for
  sandboxing.
- **Cross-SBB isolation** — SBBs share the same JVM and the same
  `MicroSleeContainer`. If you need OS-level isolation, run each SBB
  pool in its own process.
- **TLS / authentication** — micro-jainslee is a runtime, not a network
  server. The transport layer (Netty, Servlet, JMS) is the embedding
  app's responsibility.
- **Production-grade audit logging** — the SimpleTracePort writes to
  log4j2 at INFO. For tamper-evident audit trails, integrate with
  an external SIEM (e.g. Splunk, Datadog) at the embedding layer.

## Known limitations

These are explicitly tracked and not considered vulnerabilities:

1. **Reflection-based VT executor** (`MicroSleeExecutors.newVirtualThreadPerTaskExecutor`)
   — uses `Method.invoke` to keep the bytecode Java 8 compatible.
   On Java 21+ this is the spec-blessed path. On older JVMs it falls
   back to `Executors.newCachedThreadPool()`. The reflectively
   acquired method reference is the spec-mandated signature — no
   attacker-controlled input reaches the reflective invocation.
2. **Dual license** — the dual-license arrangement (GPLv3 + Commercial)
   is governance, not a security boundary. Contributors must read
   `LICENSE` and `COMMERCIAL_LICENSE.md` before signing a CLA.
3. **No code signing** — released JARs are signed by Maven Central
   once published; intermediate builds are not signed. If you depend
   on integrity verification, build from source in a controlled
   environment.

## Acknowledgements

We thank the following reporters (none yet) for responsible disclosure.

---

*Security policy version 1.0 — maintained alongside micro-jainslee 1.1.0.*
