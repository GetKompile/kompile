#!/bin/bash
# Build ONLY kompile native images (skip DL4J backend + Java module build)
# Assumes nd4j-* JARs and kompile JARs are already in the local Maven repo.
#
# Usage:
#   ./build-kompile-native-only.sh                         # all native images
#   NATIVE_TARGETS=cli,app ./build-kompile-native-only.sh  # specific targets only
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/build-common.sh"
kompile_build_for_platform "linux-x86_64" 1 1 0 1  # skip dl4j, skip java, build native, skip dist
