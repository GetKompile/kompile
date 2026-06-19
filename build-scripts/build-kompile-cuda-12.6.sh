#!/bin/bash
# Build DL4J CUDA 12.6 backend + kompile native image
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/build-common.sh"
VARIANT=cuda NATIVE_TARGETS=all kompile_build_for_platform linux-x86_64-cuda-12.6
