#!/usr/bin/env bash
# Fetch 3GPP Rel-18 5GC OpenAPI YAML packages for catalog generation.
#
# Official source: https://forge.3gpp.org/rep/all/5G_APIs (branch REL-18)
# Public mirror (default): https://github.com/jdegre/5GC_APIs (branch Rel-18)
#
# Usage:
#   ./tools/fetch-rel18-openapi.sh
#   ./tools/fetch-rel18-openapi.sh /path/to/cache
#   SBI_OPENAPI_SOURCE=forge ./tools/fetch-rel18-openapi.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST="${1:-${MODULE_ROOT}/tools/sbi-openapi-cache/Rel-18}"
SOURCE="${SBI_OPENAPI_SOURCE:-github}"

GITHUB_URL="https://github.com/jdegre/5GC_APIs/archive/refs/heads/Rel-18.tar.gz"
# Forge archive often returns 403 for anonymous clients; prefer GitHub mirror.
FORGE_URL="https://forge.3gpp.org/rep/all/5G_APIs/-/archive/REL-18/5G_APIs-REL-18.tar.gz"

case "${SOURCE}" in
  github|mirror) URL="${GITHUB_URL}" ;;
  forge|3gpp)    URL="${FORGE_URL}" ;;
  *)
    echo "Unknown SBI_OPENAPI_SOURCE=${SOURCE} (use github|forge)" >&2
    exit 2
    ;;
esac

mkdir -p "$(dirname "${DEST}")"
TMP="$(mktemp -d)"
cleanup() { rm -rf "${TMP}"; }
trap cleanup EXIT

ARCHIVE="${TMP}/rel18.tgz"
echo "[fetch-rel18] downloading ${URL}"
if ! curl -fsSL --max-time 300 -L -o "${ARCHIVE}" "${URL}"; then
  echo "[fetch-rel18] download failed. If forge returned 403, use the GitHub mirror:" >&2
  echo "  SBI_OPENAPI_SOURCE=github $0" >&2
  exit 1
fi

echo "[fetch-rel18] extracting → ${DEST}"
rm -rf "${DEST}"
mkdir -p "${DEST}"
tar -xzf "${ARCHIVE}" -C "${TMP}"
# Archive root is typically 5GC_APIs-Rel-18/ or 5G_APIs-REL-18/
ROOT="$(find "${TMP}" -mindepth 1 -maxdepth 1 -type d ! -name rel18.tgz | head -1)"
if [[ -z "${ROOT}" ]]; then
  echo "[fetch-rel18] unexpected archive layout" >&2
  exit 1
fi
# Copy YAML/JSON (and README) into DEST flat-enough tree
shopt -s nullglob
cp -a "${ROOT}/." "${DEST}/"

YAML_COUNT="$(find "${DEST}" -type f \( -name '*.yaml' -o -name '*.yml' \) | wc -l | tr -d ' ')"
echo "[fetch-rel18] done: ${YAML_COUNT} YAML files in ${DEST}"
echo "[fetch-rel18] next:"
echo "  mvn -pl vendor-ras/ra-openapi -Pgenerate-sbi-catalog \\"
echo "    -Dsbi.catalog.input=${DEST} \\"
echo "    -Dsbi.catalog.output=src/main/resources/sbi-openapi/catalog.json \\"
echo "    exec:java"
