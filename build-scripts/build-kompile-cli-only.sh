#!/usr/bin/env bash
# Build the cli-only distribution: native CLI plus its delegated CLI images,
# with NO web server, NO bundled runtime, and NO ND4J backend closure unless a
# backend platform is selected.
#
# Ships bin/kompile, kompile-model, kompile-agent, kompile-app-cli and
# kompile-component. This is the smallest payload that still supports every
# CLI command; server components are absent by design.
#
# Usage:
#   ./build-scripts/build-kompile-cli-only.sh [platform] [options...]
#   ./build-scripts/build-kompile-cli-only.sh linux-x86_64
#   ./build-scripts/build-kompile-cli-only.sh macosx-arm64 --skip-dl4j --skip-java
#   # Backend-qualified cli-only archive (adds local model/pipeline workers):
#   ./build-scripts/build-kompile-cli-only.sh linux-x86_64-cuda-12.9
#
# All build-kompile-platform.sh options are supported (--skip-dl4j,
# --skip-java, --setup, --dl4j-repository, ...). Platform may also be given
# through KOMPILE_PLATFORM.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_ONLY_NATIVE_TARGETS="cli,model,agent,app-cli,component"

export VARIANT=cli-only
export NATIVE_TARGETS="${NATIVE_TARGETS:-${CLI_ONLY_NATIVE_TARGETS}}"

if [ $# -gt 0 ]; then
  exec bash "${SCRIPT_DIR}/build-kompile-platform.sh" "$@"
fi
case "$(uname -s)" in
  Linux*)  os="linux" ;;
  Darwin*) os="macosx" ;;
  *)       os="windows" ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  arch="x86_64" ;;
  aarch64|arm64) arch="arm64" ;;
  *)             arch="$(uname -m)" ;;
esac
exec bash "${SCRIPT_DIR}/build-kompile-platform.sh" "${KOMPILE_PLATFORM:-${os}-${arch}}"
