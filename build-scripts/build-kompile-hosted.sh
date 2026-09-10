#!/usr/bin/env bash
# Build the hosted distribution: the full web product with NO local model
# backend. Ships the CLI images, kompile-server (app-main), kompile-chat,
# kompile-crawl-manager and kompile-model-staging; embeddings and LLM calls
# resolve through API keys, so no ND4J backend is bundled or built.
#
# Usage:
#   ./build-scripts/build-kompile-hosted.sh [platform] [options...]
#   ./build-scripts/build-kompile-hosted.sh linux-x86_64
#   ./build-scripts/build-kompile-hosted.sh macosx-arm64 --skip-dl4j --skip-java
#
# All build-kompile-platform.sh options are supported (--skip-dl4j,
# --skip-java, --setup, --dl4j-repository, ...). Platform may also be given
# through KOMPILE_PLATFORM.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOSTED_NATIVE_TARGETS="cli,model,agent,app-cli,component,app,chat,crawl-manager,staging"

export VARIANT=hosted
export NATIVE_TARGETS="${NATIVE_TARGETS:-${HOSTED_NATIVE_TARGETS}}"

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
