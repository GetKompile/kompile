#!/usr/bin/env bash
# Build the full distribution: CPU (nd4j-native) product with a bundled Java
# runtime, every web server, model staging, and the request-scoped local
# model/pipeline runtimes. This is the 'full' archive the installer prefers.
#
# Usage:
#   ./build-scripts/build-kompile-full.sh [platform] [options...]
#   ./build-scripts/build-kompile-full.sh linux-x86_64
#   ./build-scripts/build-kompile-full.sh macosx-arm64 --skip-dl4j --skip-java
#
# For backend-qualified product distros (CUDA, ZLUDA, OneDNN, Windows) use the
# dedicated build-kompile-cuda*.sh / build-kompile-rocm.sh / build-kompile-cpu-*.sh
# presets instead — they select the matching backend profile and classifier.
#
# All build-kompile-platform.sh options are supported (--skip-dl4j,
# --skip-java, --setup, --dl4j-repository, ...). Platform may also be given
# through KOMPILE_PLATFORM.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FULL_NATIVE_TARGETS="cli,model,agent,app-cli,component,app,chat,crawl-manager,staging,model-serving,pipeline-serving"

export VARIANT=full
export NATIVE_TARGETS="${NATIVE_TARGETS:-${FULL_NATIVE_TARGETS}}"

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
