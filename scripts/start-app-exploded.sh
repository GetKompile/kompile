#!/usr/bin/env bash
# Workaround for Spring Boot 3.2.5 loader zip64 failure ("Position must not be
# negative") on the 4.89GB kompile-app-main exec jar. Launches the same app from an
# unpacked copy of the jar — no nested-jar loader involved.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNPACKED=/home/agibsonccc/.kompile/components/kompile-app-main/0.1.0-SNAPSHOT/unpacked
LOG_DIR="$ROOT/.kompile/state/logs"
PID_DIR="$ROOT/.kompile/state/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"
if [ ! -f "$UNPACKED/BOOT-INF/classes/ai/kompile/app/MainApplication.class" ]; then
  echo "Unpacked app not found at $UNPACKED — run: cd $UNPACKED && jar -xf <exec-jar>" >&2
  exit 2
fi
nohup bash -lc "cd '$ROOT' && exec java -Xmx8g -Dfile.encoding=UTF-8 -Dorg.bytedeco.javacpp.pathsFirst=true -cp '$UNPACKED/BOOT-INF/classes:$UNPACKED/BOOT-INF/lib/*' ai.kompile.app.MainApplication" > "$LOG_DIR/app.log" 2>&1 &
echo $! > "$PID_DIR/app.pid"
echo "Started app (exploded) with PID $(cat "$PID_DIR/app.pid")"
