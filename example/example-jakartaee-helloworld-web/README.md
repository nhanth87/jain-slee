# example-jakartaee-helloworld-web

**Deploy unit = directory `dist/`** (Digicom-ET standard). **Never WAR.**

```
dist/helloworld-jakartaee-jainslee/
  run.sh
  *.jar
  html/          ← UI ONLY (*.html *.js *.css — no jars)
  configs/log4j2.xml
  lib/
  logs/
```

UI source: repo `html/` → packaged into `dist/.../html/`.

## Build / run

```bash
# JDK 25
./build/package-dist.sh
./dist/helloworld-jakartaee-jainslee/run.sh
# UI: open dist/.../html/index.html  (or python -m http.server -d html 8080)
# RA: http://127.0.0.1:8081/hello
```

## Logging

**Log4j2 ONLY** — `log4j-api` + `log4j-core` **2.24.3** + `configs/log4j2.xml`.

## Host note

Lab dist uses `HelloWorldMain` (embedded). Real EE servers still use `adapter-jakartaee` (`MicroSleeContainerStartup`) — same UI rule: static files live under `html/` in the deploy directory, not inside a WAR.
