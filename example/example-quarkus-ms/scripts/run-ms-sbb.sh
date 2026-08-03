#!/usr/bin/env bash
# Micro-services node that hosts only "http-sbb" (HTTP gateway SBB) on :8082.
# Start run-ms-ra.sh first, then this script.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="${JAVA_HOME}/bin:${PATH}"

mvn -q -DskipTests package

echo "==> node-sbb on :8082 (calls http-ra via Infinispan queue)"
exec java ${JAVA_OPTS:-} \
  -Djainslee.deployment.resource=deployment-microservices.yml \
  -Djainslee.node-id=node-sbb \
  -Djainslee.ms.cluster-enabled=true \
  -Djainslee.ms.cluster-initial-hosts=127.0.0.1[7800] \
  -Dhttp.ra.port=8082 \
  -Djava.net.preferIPv4Stack=true \
  -Djgroups.bind_addr=127.0.0.1 \
  -jar target/quarkus-app/quarkus-run.jar
