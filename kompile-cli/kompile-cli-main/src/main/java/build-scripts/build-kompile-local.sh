#!/usr/bin/env bash
# Build the lean folder-local Kompile distribution for one DL4J platform.
#
# The archive contains only:
#   - kompile (the standard chat/MCP CLI; local crawl is an internal CLI mode)
#   - kompile-model-serving
#   - kompile-pipeline-serving
#   - the selected backend's validated side-loaded native closure and SDX assets
#
# Usage:
#   ./build-scripts/build-kompile-local.sh linux-x86_64 --setup
#   ./build-scripts/build-kompile-local.sh linux-x86_64-cuda-12.9 --setup
#   ./build-scripts/build-kompile-local.sh linux-x86_64-cuda-12.9 \
#       --skip-dl4j --skip-java
#
# All build-kompile-platform.sh options are supported. In particular, --setup
# delegates host package installation (apt/dnf/brew/etc.) to the existing DL4J
# platform setup playbook rather than maintaining a second prerequisite list.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_NATIVE_TARGETS="cli,model-serving,pipeline-serving"

export VARIANT=local
export NATIVE_TARGETS="${NATIVE_TARGETS:-${LOCAL_NATIVE_TARGETS}}"

exec bash "${SCRIPT_DIR}/build-kompile-platform.sh" "$@"
