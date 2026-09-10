#!/bin/bash
# Build DL4J Windows x86_64 backend + kompile native image
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/build-common.sh"
VARIANT=cpu-intel NATIVE_TARGETS=all kompile_build_for_platform windows-x86_64
