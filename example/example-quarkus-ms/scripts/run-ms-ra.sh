#!/usr/bin/env bash
# Micro-services INGRESS node: http-ra + http-aux + MsGatewaySbb on :8081.
# Runs from target/node-ra/ (private copy) — never shares jars with node-sbb.
#
# Start this first, then scripts/run-ms-sbb.sh.
# Rebuild: stop both JVMs, then MS_REBUILD=1 ./scripts/prepare-ms-nodes.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="${JAVA_HOME}/bin:${PATH}"

"${ROOT}/scripts/prepare-ms-nodes.sh"

APP="${ROOT}/target/node-ra/quarkus-run.jar"
if [[ ! -f "${APP}" ]]; then
  echo "ERROR: missing ${APP}" >&2
  exit 1
fi

echo "==> node-ra on :8081 from ${APP}"
echo "    expect log: gatewaySbbs=true http.ra.port=8081 localServices=http-ra,http-aux"
exec java ${JAVA_OPTS:-} \
  -Djainslee.deployment.resource=deployment-microservices.yml \
  -Djainslee.node-id=node-ra \
  -Djainslee.ms.cluster-enabled=true \
  "-Djainslee.ms.cluster-initial-hosts=127.0.0.1[7800]" \
  -Dhttp.ra.port=8081 \
  -Djava.net.preferIPv4Stack=true \
  -Djgroups.bind_addr=127.0.0.1 \
  -jar "${APP}"
