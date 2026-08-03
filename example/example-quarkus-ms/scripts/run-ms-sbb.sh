#!/usr/bin/env bash
# Micro-services SBB node: hosts http-sbb only. Port :8082 is /health (no gateway).
# Runs from target/node-sbb/ (private copy) — never mvn-package into the RA JVM tree.
#
# Start run-ms-ra.sh first, then this script.
# Demo ingress is on :8081 — curl call-sbb there (ISPN → this node).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="${JAVA_HOME}/bin:${PATH}"

"${ROOT}/scripts/prepare-ms-nodes.sh"

APP="${ROOT}/target/node-sbb/quarkus-run.jar"
if [[ ! -f "${APP}" ]]; then
  echo "ERROR: missing ${APP}" >&2
  exit 1
fi

echo "==> node-sbb on :8082 from ${APP}"
echo "    expect log: gatewaySbbs=false http.ra.port=8082 localServices=http-sbb"
echo "    RA→SBB invoke logs appear here (stdout), not in curl :8082/health"
echo "    look for: [IspnQueueServer:http-sbb] received ... and [http-sbb] invoke ..."
exec java ${JAVA_OPTS:-} \
  -Djainslee.deployment.resource=deployment-microservices.yml \
  -Djainslee.node-id=node-sbb \
  -Djainslee.ms.cluster-enabled=true \
  "-Djainslee.ms.cluster-initial-hosts=127.0.0.1[7800]" \
  -Dhttp.ra.port=8082 \
  -Djava.net.preferIPv4Stack=true \
  -Djgroups.bind_addr=127.0.0.1 \
  -jar "${APP}"
