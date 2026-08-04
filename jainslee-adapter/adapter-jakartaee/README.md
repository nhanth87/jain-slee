# adapter-jakartaee

Jakarta EE 9+ host for the embedded `MicroSleeContainer` (WildFly 27+ / Payara 6+ / Open Liberty / TomEE 9+).

## Lifecycle

| Bean | Role |
|------|------|
| `MicroSleeContainerStartup` | `@Startup` EJB — build/start container, JNDI bind, `RaPortManager.registerAll` |
| `RaPortManager` | CDI — discover `@RaEntity` endpoint/command pairs |
| `MicroSleeContainerProducer` | CDI `@Produces` — JNDI lookup of container for `@Inject MicroSleeContainer` |

Do **not** `@Inject MicroSleeContainer` into `RaPortManager` (would cycle with startup). Apps: `@DependsOn("MicroSleeContainerStartup")` then inject startup or produced container.

## Telemetry

Use {@link com.microjainslee.jakartaee.TelemetryObserverSupport#install} after creating a `TelemetryPort`:

```java
TelemetryObserverSupport.install(container, telemetryPort);
```

See `example/example-jakartaee-helloworld-web`.

## Logging

**Log4j2 ONLY** — `LogManager.getLogger(Class)`. Apps must ship matching `log4j-api` + `log4j-core` (same version as jainslee-pom, currently **2.24.3**) and a `log4j2.xml`. Never SLF4J/logback/`log4j2-jboss-logmanager` dual stacks.

## Dist / deploy (Digicom standard)

**Directory `dist/` with `html/*.html|*.js|*.css` — never WAR.** See root [`AGENTS.md`](../../AGENTS.md) § DIST / DEPLOY LAYOUT. Example: `example/example-jakartaee-helloworld-web/build/package-dist.sh`.

## Scope

R&D sample host. Production Digicom OTA SMSC-GW remains **adapter-quarkus** only.
