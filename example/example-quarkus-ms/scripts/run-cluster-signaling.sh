#!/usr/bin/env bash
# Cluster node that hosts only the "signaling" service (HTTP :8081).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="${JAVA_HOME}/bin:${PATH}"

PING_DIR="${TMPDIR:-/tmp}/jainslee-ms-jgroups"
mkdir -p "$PING_DIR"

mvn -q -DskipTests package

echo "==> node-signaling on :8081 (JGroups ping dir: $PING_DIR)"
exec java ${JAVA_OPTS:-} \
  -Djainslee.deployment.resource=deployment-cluster.yml \
  -Djainslee.node-id=node-signaling \
  -Djainslee.ms.cluster-enabled=true \
  -Djainslee.ms.cluster-initial-hosts=127.0.0.1[7800] \
  -Dhttp.ra.port=8081 \
  -Djava.net.preferIPv4Stack=true \
  -Djgroups.bind_addr=127.0.0.1 \
  -jar target/quarkus-app/quarkus-run.jar
