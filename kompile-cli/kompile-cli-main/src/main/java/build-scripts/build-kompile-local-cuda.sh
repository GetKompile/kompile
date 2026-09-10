#!/usr/bin/env bash
# Lean local MCP/model distribution with the Linux x86_64 CUDA 12.9 backend.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/build-kompile-local.sh" linux-x86_64-cuda-12.9 "$@"
