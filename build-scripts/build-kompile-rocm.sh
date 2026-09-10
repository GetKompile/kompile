#!/bin/bash
# Build Kompile against an installed/published version-qualified ZLUDA lane.
# ROCm 7.2.4 remains the default; 10.0.0 is an opt-in candidate.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/build-common.sh"
ROCM_VERSION="${KOMPILE_ROCM_VERSION:-7.2.4}"
case "${ROCM_VERSION}" in
  7.2.4|10.0.0) ;;
  *) log "ERROR: unsupported Kompile ROCm version '${ROCM_VERSION}'"; exit 2 ;;
esac
PLATFORM="linux-x86_64-cuda-12.9-zluda-rocm-${ROCM_VERSION}"
VARIANT=amd-zluda NATIVE_TARGETS=all kompile_build_for_platform "${PLATFORM}" 1
