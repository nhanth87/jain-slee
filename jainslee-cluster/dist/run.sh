#!/usr/bin/env bash
# Launch ClusterWitness (directory layout: thin jar + lib/).
# Third JGroups member for a two-node active/active pair (ADR 0007 D12):
# no RA, no socket, no dialog — it only votes on the fenced lease caches.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

# JDK 25 only (refuse anything else — same guard as every dist/run.sh).
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/25"; do
    if [[ -x "${cand}/bin/java" ]]; then
      export JAVA_HOME="${cand}"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "error: JDK 25 required. Set JAVA_HOME to zulu-25 (mise)." >&2
  exit 1
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

JAVA_VER="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 || true)"
if ! echo "${JAVA_VER}" | grep -qE 'version "25'; then
  echo "error: JAVA_HOME must be JDK 25, got: ${JAVA_VER}" >&2
  exit 1
fi

JAR="cluster-witness.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "error: ${JAR} missing — run dist/build-witness-dist.sh first." >&2
  exit 1
fi

NODE_ID="${MICROJANSLEE_NODE_ID:-witness-1}"
STACK="${MICROJANSLEE_STACK:-tcp}"
HOSTS="${MICROJANSLEE_INITIAL_HOSTS:-}"
RA_NAMES="${MICROJANSLEE_WITNESS_RA_NAMES:-ra-jss7}"

ARGS=("-Dmicrojainslee.container.cluster-node-id=${NODE_ID}"
      "-Dmicrojainslee.container.cluster-stack=${STACK}"
      "-Dmicrojainslee.witness.ra-names=${RA_NAMES}")
if [[ -n "${HOSTS}" ]]; then
  ARGS+=("-Dmicrojainslee.container.cluster-initial-hosts=${HOSTS}")
fi

echo "JAVA_HOME=${JAVA_HOME}"
echo "witness node=${NODE_ID} stack=${STACK} hosts=${HOSTS:-<multicast>} ra-names=${RA_NAMES}"

exec "${JAVA_HOME}/bin/java" \
  -Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager \
  -Dorg.jboss.logging.provider=log4j2 \
  -Dlog4j2.configurationFile="${SCRIPT_DIR}/configs/log4j2.xml" \
  "${ARGS[@]}" \
  -cp "${JAR}:lib/*" \
  com.microjainslee.cluster.ClusterWitness "$@"
