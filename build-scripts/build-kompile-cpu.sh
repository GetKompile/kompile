#!/bin/bash
# Build DL4J CPU backend + kompile native image
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/build-common.sh"
VARIANT=cpu-intel NATIVE_TARGETS=all kompile_build_for_platform linux-x86_64
