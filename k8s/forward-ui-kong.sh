#!/bin/bash
set -euo pipefail

NAMESPACE="faq-assistance"
UI_SERVICE="faq-ui"
KONG_SERVICE="kong-gateway"
UI_PORT="5173:5173"
KONG_PORT="8000:8000"

UI_LOG="/tmp/faq-ui-port-forward.log"
KONG_LOG="/tmp/faq-kong-port-forward.log"

cleanup() {
  if [[ -n "${UI_PID:-}" ]] && kill -0 "$UI_PID" 2>/dev/null; then
    kill "$UI_PID" 2>/dev/null || true
  fi
  if [[ -n "${KONG_PID:-}" ]] && kill -0 "$KONG_PID" 2>/dev/null; then
    kill "$KONG_PID" 2>/dev/null || true
  fi
}

trap cleanup EXIT INT TERM

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found in PATH"
  exit 1
fi

start_forward() {
  local service="$1"
  local ports="$2"
  local log_file="$3"

  kubectl port-forward -n "$NAMESPACE" "svc/$service" "$ports" >"$log_file" 2>&1 &
  local pf_pid=$!

  sleep 2
  if ! kill -0 "$pf_pid" 2>/dev/null; then
    echo "Failed to start port-forward for $service ($ports)"
    if [[ -f "$log_file" ]]; then
      tail -n 20 "$log_file"
    fi
    return 1
  fi

  echo "$pf_pid"
}

echo "Starting port-forwards in namespace $NAMESPACE..."

UI_PID=$(start_forward "$UI_SERVICE" "$UI_PORT" "$UI_LOG")
KONG_PID=$(start_forward "$KONG_SERVICE" "$KONG_PORT" "$KONG_LOG")

echo "UI port-forward PID: $UI_PID (localhost:5173)"
echo "Kong port-forward PID: $KONG_PID (localhost:8000)"
echo "Logs: $UI_LOG , $KONG_LOG"
echo ""
echo "Open: http://localhost:5173"
echo "Press Ctrl+C to stop both forwards."

wait "$UI_PID" "$KONG_PID"
