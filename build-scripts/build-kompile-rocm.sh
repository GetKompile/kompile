#!/bin/bash
# Build DL4J ROCm/ZLUDA backend + kompile native image
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/build-common.sh"
VARIANT=amd-zluda NATIVE_TARGETS=all kompile_build_for_platform linux-x86_64-rocm-6.4
