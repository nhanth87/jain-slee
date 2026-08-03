#!/usr/bin/env bash
# Capture loopback traffic for the micro-services demo (Wireshark / tcpdump).
# Filter: HTTP RA ports + JGroups fabric port.
#
# Usage:
#   sudo ./scripts/capture-lo.sh              # writes /tmp/quarkus-ms-lo.pcap
#   sudo ./scripts/capture-lo.sh /tmp/x.pcap
#
# Then open the pcap in Wireshark, or live-capture with:
#   wireshark -i lo -f "tcp port 8081 or tcp port 8082 or tcp port 7800"
set -euo pipefail

OUT="${1:-/tmp/quarkus-ms-lo.pcap}"
FILTER="tcp port 8081 or tcp port 8082 or tcp port 7800"

echo "Capturing on lo → ${OUT}"
echo "Filter: ${FILTER}"
echo "Start run-ms-ra.sh, then run-ms-sbb.sh, then:"
echo "  curl -s -X POST 'http://127.0.0.1:8081/api/ms/http-sbb?op=ping' -H 'Content-Type: text/plain' -d ''"
echo "  # alias: /api/demo/call-sbb?op=ping"
echo "Ctrl-C to stop."

if command -v tcpdump >/dev/null 2>&1; then
  exec tcpdump -i lo -n -w "${OUT}" ${FILTER}
fi

if command -v tshark >/dev/null 2>&1; then
  exec tshark -i lo -n -w "${OUT}" -f "${FILTER}"
fi

echo "Need tcpdump or tshark on PATH" >&2
exit 1
