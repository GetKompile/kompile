#!/bin/bash
# deploy-cli-jar.sh — thin shim; the canonical entry point is now ../redeploy.sh
#
# History (kept for muscle memory): this script did the atomic `install -T` /
# rename(2) CLI jar swap with the thin-jar guard and /tmp backups. All of that
# behavior — plus backend lane selection from ~/.kompile/.dist-info.json,
# sibling/chat/serving scope, lane validation, .boot-inf-extracted purge, and
# `kompile doctor` — now lives in ../redeploy.sh.
#
#   ./redeploy.sh            # same as this script's old default (--main + siblings)
#   ./redeploy.sh --all      # also chat handoff + model/pipeline serving jars

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec bash "${SCRIPT_DIR}/../redeploy.sh" "$@"
