#!/bin/bash
# Build DL4J CPU backend for ARM64 + kompile native image
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/build-common.sh"
VARIANT=cpu-arm NATIVE_TARGETS=all kompile_build_for_platform linux-arm64
