#!/usr/bin/env bash
# Build a classic directory distribution for the ClusterWitness (NOT an uber-jar):
#
#   jainslee-cluster/dist/cluster-witness/
#     run.sh
#     cluster-witness.jar      # jainslee-cluster thin jar
#     lib/                     # jainslee-api, infinispan, jgroups, log4j, ...
#     configs/log4j2.xml
#     logs/
#
# Usage: ./dist/build-witness-dist.sh   (from jainslee-cluster/)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${MODULE_DIR}"

DIST_ROOT="${DIST_ROOT:-${MODULE_DIR}/dist}"
DIST_DIR="${DIST_ROOT}/cluster-witness"

# JDK 25 only (mise zulu-25).
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

# Packages jainslee-cluster plus jainslee-api (provided-scope jainslee-core is
# needed at runtime for MicroSleeConfiguration — copied explicitly below).
mvn -B -ntp package -DskipTests
mvn -B -ntp dependency:copy-dependencies -DincludeScope=runtime \
  -DoutputDirectory=target/witness-lib -Dmdep.prependGroupId=false >/dev/null
# jainslee-core is provided-scope but required at runtime (MicroSleeConfiguration):
# copy it explicitly alongside the runtime deps.
VER="$(mvn -B -ntp -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)"
mvn -B -ntp dependency:copy "-Dartifact=com.microjainslee:jainslee-core:${VER}" \
  -DoutputDirectory=target/witness-lib >/dev/null

# Log4j2 backend: test-scoped in the module pom (the embedder normally
# supplies it) — the witness ships its own, same version as the log4j-api
# pinned in jainslee-cluster/pom.xml.
mvn -B -ntp dependency:copy "-Dartifact=org.apache.logging.log4j:log4j-core:2.26.1" \
  -DoutputDirectory=target/witness-lib >/dev/null

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}/lib" "${DIST_DIR}/configs" "${DIST_DIR}/logs"
MODULE_JAR="$(ls -1 target/jainslee-cluster-*.jar | grep -v tests | grep -v sources | head -1)"
cp -a "${MODULE_JAR}" "${DIST_DIR}/cluster-witness.jar"
cp -a target/witness-lib/. "${DIST_DIR}/lib/"
# The thin module jar also lands in target/ root; keep only one copy in dist.
rm -f "${DIST_DIR}"/lib/jainslee-cluster-*.jar
cp -a "${SCRIPT_DIR}/run.sh" "${DIST_DIR}/run.sh"
cp -a "${SCRIPT_DIR}/log4j2.xml" "${DIST_DIR}/configs/log4j2.xml"
chmod +x "${DIST_DIR}/run.sh"

echo "witness dist: ${DIST_DIR}"
ls "${DIST_DIR}" "${DIST_DIR}/lib" | head -30
