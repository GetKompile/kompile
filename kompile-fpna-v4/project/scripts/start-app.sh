#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/.kompile/state/logs"
PID_DIR="$ROOT/.kompile/state/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"
COMMAND="${KOMPILE_APP_COMMAND:-}"
if [ -z "$COMMAND" ]; then
  echo "Set KOMPILE_APP_COMMAND to start the Kompile app for this project." >&2
  exit 2
fi
nohup bash -lc "$COMMAND" > "$LOG_DIR/app.log" 2>&1 &
echo $! > "$PID_DIR/app.pid"
echo "Started app with PID $(cat "$PID_DIR/app.pid")"
