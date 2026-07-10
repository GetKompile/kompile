#!/usr/bin/env bash
set -euo pipefail

ROOT="${KOMPILE_PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
WORKFLOW_ID="${KOMPILE_VLM_OCR_WORKFLOW:-vlm-ocr-ingest}"

KOMPILE_BIN="${KOMPILE_BIN:-kompile}"

if [[ "${1:-}" == "--dry-run" ]]; then
  exec "$KOMPILE_BIN" project workflow-run --root "$ROOT" --id "$WORKFLOW_ID" --dry-run
fi

exec "$KOMPILE_BIN" project workflow-run --root "$ROOT" --id "$WORKFLOW_ID" "$@"
