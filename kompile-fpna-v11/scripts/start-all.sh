#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/start-staging.sh"
"$ROOT/scripts/start-serving.sh"
"$ROOT/scripts/start-app.sh"
