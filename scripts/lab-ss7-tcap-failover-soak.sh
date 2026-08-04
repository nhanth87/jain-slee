#!/usr/bin/env bash
# Lab helper: poll TCAP failover metrics (ADR 0001 P2).
# Does NOT claim production multi-ASP HA — see docs/lab/ss7-multi-asp-failover.md
set -euo pipefail

METRICS_URL="${1:-}"
ROUNDS="${ROUNDS:-30}"
SLEEP_SECS="${SLEEP_SECS:-2}"

echo "=== SS7 TCAP failover lab soak (P2 metrics) ==="
echo "Not production HA. Expect multi-ASP same-AS topology."
echo

EXPECTED=(
  ss7_tcap_failover_export_ok_total
  ss7_tcap_failover_export_fail_total
  ss7_tcap_failover_import_ok_total
  ss7_tcap_failover_import_fail_total
  ss7_tcap_failover_continue_miss_total
  ss7_tcap_failover_continue_resolve_fail_total
  ss7_tcap_failover_takeover_ok_total
  ss7_tcap_failover_takeover_fail_total
  ss7_tcap_sticky_reject_total
  ss7_tcap_sticky_miss_total
)

if [[ -z "${METRICS_URL}" || "${METRICS_URL}" == "--help" ]]; then
  echo "Usage: $0 <metrics-json-url>"
  echo "  e.g. $0 http://127.0.0.1:8088/admin/ss7/failover-metrics"
  echo
  echo "Expected counter names (from TcapFailoverMetrics.snapshot()):"
  printf '  %s\n' "${EXPECTED[@]}"
  echo
  echo "Manual: call Ss7ResourceAdaptor.failoverMetrics().snapshot() on the survivor."
  exit 0
fi

fail=0
for ((i = 1; i <= ROUNDS; i++)); do
  echo "--- round ${i}/${ROUNDS} @ $(date -Iseconds) ---"
  if ! body="$(curl -fsS --max-time 5 "${METRICS_URL}")"; then
    echo "WARN: metrics fetch failed (${METRICS_URL})"
    fail=1
  else
    echo "${body}"
    if command -v jq >/dev/null 2>&1; then
      import_fail="$(echo "${body}" | jq -r '.ss7_tcap_failover_import_fail_total // .["ss7_tcap_failover_import_fail_total"] // 0')"
      if [[ "${import_fail}" != "0" && "${import_fail}" != "null" ]]; then
        echo "FAIL: import_fail_total=${import_fail}"
        fail=1
      fi
    fi
  fi
  sleep "${SLEEP_SECS}"
done

if [[ "${fail}" -ne 0 ]]; then
  echo "Soak finished with warnings/failures — do not claim production HA."
  exit 1
fi
echo "Soak poll complete. Confirm CONTINUE resume manually per docs/lab/ss7-multi-asp-failover.md"
