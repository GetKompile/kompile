#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/.kompile/state/logs"
PID_DIR="$ROOT/.kompile/state/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"
COMMAND="${KOMPILE_STAGING_COMMAND:-}"
if [ -z "$COMMAND" ]; then
  echo "Set KOMPILE_STAGING_COMMAND to start model staging for this project." >&2
  exit 2
fi
nohup bash -lc "$COMMAND" > "$LOG_DIR/staging.log" 2>&1 &
echo $! > "$PID_DIR/staging.pid"
echo "Started staging with PID $(cat "$PID_DIR/staging.pid")"
