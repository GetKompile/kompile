#!/usr/bin/env bash
# CUDA 13.1 product distribution on Linux x86_64 or ARM64 (including Blackwell).
# Forward platform-driver options such as --skip-dl4j; do not ignore them.
# For installed local DL4J artifacts without SDK/source collection, prefer:
#   ./build-dist.sh cli-only --platform linux-arm64 --backend-profile cuda-13.1
# Native images require GraalVM JDK 21. The plain classifier includes Triton.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "$(uname -s):$(uname -m)" in
  Linux:x86_64|Linux:amd64) PLATFORM=linux-x86_64-cuda-13.1 ;;
  Linux:aarch64|Linux:arm64) PLATFORM=linux-arm64-cuda-13.1 ;;
  *) printf 'CUDA 13.1 wrapper requires Linux x86_64 or ARM64\n' >&2; exit 1 ;;
esac
export VARIANT="${VARIANT:-cuda}"
export NATIVE_TARGETS="${NATIVE_TARGETS:-all}"
exec bash "${SCRIPT_DIR}/build-kompile-platform.sh" "${PLATFORM}" "$@"
