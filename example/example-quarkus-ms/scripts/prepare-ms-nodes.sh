#!/usr/bin/env bash
# Build once and stage separate quarkus-app copies for each MS node.
#
# Two JVMs must NEVER share target/quarkus-app: a second `mvn package` (or
# `mvn clean`) while a node is running deletes jars still open → NoSuchFileException
# and a dead HTTP listener with no gateway logs.
#
# Usage:
#   ./scripts/prepare-ms-nodes.sh           # package if needed, refresh both node dirs
#   MS_REBUILD=1 ./scripts/prepare-ms-nodes.sh   # force mvn package + refresh
#
# Stop both JVMs before MS_REBUILD / mvn clean.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0}"
export PATH="${JAVA_HOME}/bin:${PATH}"

SRC="${ROOT}/target/quarkus-app"
RA_DIR="${ROOT}/target/node-ra"
SBB_DIR="${ROOT}/target/node-sbb"

need_package=0
if [[ "${MS_REBUILD:-0}" == "1" ]]; then
  need_package=1
elif [[ ! -f "${SRC}/quarkus-run.jar" ]]; then
  need_package=1
fi

if [[ "${need_package}" == "1" ]]; then
  echo "==> mvn package (do not run while JVMs use target/node-* or target/quarkus-app)"
  mvn -q -DskipTests package
fi

if [[ ! -f "${SRC}/quarkus-run.jar" ]]; then
  echo "ERROR: ${SRC}/quarkus-run.jar missing after package" >&2
  exit 1
fi

stage_node() {
  local dest="$1"
  local label="$2"
  if [[ "${MS_REBUILD:-0}" != "1" && -f "${dest}/quarkus-run.jar" ]]; then
    echo "==> keep existing ${label} (${dest})"
    return
  fi
  echo "==> stage ${label} → ${dest}"
  rm -rf "${dest}"
  cp -a "${SRC}" "${dest}"
}

stage_node "${RA_DIR}" "node-ra"
stage_node "${SBB_DIR}" "node-sbb"
echo "==> ready: ${RA_DIR}/quarkus-run.jar , ${SBB_DIR}/quarkus-run.jar"
