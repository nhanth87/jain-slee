#!/usr/bin/env bash
# Run Quarkus MS example in single mode (Direct calls, one process).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="${JAVA_HOME}/bin:${PATH}"

echo "==> Building example-quarkus-ms (needs micro-jainslee 1.2.0-SNAPSHOT installed)"
mvn -q -DskipTests package

echo "==> Starting single-node demo on http://127.0.0.1:8080 (ra-http-server)"
exec java ${JAVA_OPTS:-} \
  -Djainslee.ms.cluster-enabled=false \
  -Dhttp.ra.port=8080 \
  -jar target/quarkus-app/quarkus-run.jar
