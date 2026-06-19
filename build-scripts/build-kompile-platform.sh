#!/usr/bin/env bash
###############################################################################
# build-kompile-platform.sh
#
# Build kompile for a single DL4J platform target.
# End-to-end: DL4J backend -> kompile Java -> native images -> distribution.
#
# Usage:
#   ./build-kompile-platform.sh <platform> [options]
#   ./build-kompile-platform.sh linux-x86_64-cuda-12.9
#   ./build-kompile-platform.sh linux-x86_64 --native-targets cli,app,staging
#   ./build-kompile-platform.sh macosx-arm64 --skip-dl4j --skip-java
#   DEPLOY=1 ./build-kompile-platform.sh linux-x86_64
#
# Options:
#   --native-targets T   Comma-separated native image targets (default: cli)
#                        Valid: cli, app, staging
#   --variant V          Distribution variant (default: auto-detect from platform)
#                        Valid: cli-only, hosted, cpu-intel, cpu-arm, cuda, amd-zluda
#   --dl4j-branch B      DL4J branch to clone/checkout (default: master)
#   --kompile-branch B   Kompile branch to checkout (default: main)
#   --dl4j-root DIR      Path to DL4J checkout (default: ../deeplearning4j, cloned if missing)
#   --skip-dl4j          Skip DL4J backend build (use pre-installed nd4j JARs)
#   --skip-java          Skip kompile Java module build
#   --skip-native        Skip native image builds
#   --skip-dist          Skip distribution assembly
#   --setup              Auto-install build dependencies (delegates to DL4J)
#   --list               List all supported platforms
#
# Environment:
#   DL4J_PROJECT_ROOT  - path to deeplearning4j checkout (default: ../deeplearning4j)
#   DL4J_BRANCH        - DL4J branch (default: master)
#   KOMPILE_BRANCH     - Kompile branch (default: main)
#   GRAALVM_HOME       - GraalVM installation path
#   MVN                - path to mvn binary
#   BUILD_THREADS      - parallel compilation threads
###############################################################################
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Parse args before sourcing (so overrides take effect)
PLATFORM=""
DO_SETUP=0
SKIP_DL4J=0
SKIP_JAVA=0
SKIP_NATIVE=0
SKIP_DIST=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --native-targets)   NATIVE_TARGETS="$2"; shift 2 ;;
    --variant)          VARIANT="$2"; shift 2 ;;
    --dl4j-branch)      DL4J_BRANCH="$2"; shift 2 ;;
    --kompile-branch)   KOMPILE_BRANCH="$2"; shift 2 ;;
    --dl4j-root)        DL4J_PROJECT_ROOT="$2"; shift 2 ;;
    --skip-dl4j)        SKIP_DL4J=1; shift ;;
    --skip-java)        SKIP_JAVA=1; shift ;;
    --skip-native)      SKIP_NATIVE=1; shift ;;
    --skip-dist)        SKIP_DIST=1; shift ;;
    --setup)            DO_SETUP=1; shift ;;
    --list|-l)
      source "${SCRIPT_DIR}/build-common.sh"
      echo "Kompile build platforms:"
      printf '  %s\n' "${KOMPILE_PLATFORMS[@]}"
      exit 0 ;;
    --help|-h)
      head -35 "$0" | tail -33
      exit 0 ;;
    -*)
      echo "Unknown option: $1" >&2; exit 1 ;;
    *)
      PLATFORM="$1"; shift ;;
  esac
done

if [ -z "${PLATFORM}" ]; then
  echo "Usage: $0 [options] <platform>"
  echo "Run '$0 --list' for supported platforms."
  exit 1
fi

# Source shared build library
source "${SCRIPT_DIR}/build-common.sh"

# Auto-detect variant from platform if not specified
if [ -z "${VARIANT:-}" ]; then
  case "${PLATFORM}" in
    *cuda*)  VARIANT="cuda" ;;
    *rocm*)  VARIANT="amd-zluda" ;;
    *arm64*) VARIANT="cpu-arm" ;;
    *)       VARIANT="cpu-intel" ;;
  esac
fi

# Auto-provision if requested
if [ "${DO_SETUP}" -eq 1 ] && [ "${_DL4J_COMMON_LOADED}" -eq 1 ]; then
  auto_setup_host "${PLATFORM}"
fi

log "Kompile platform build"
log "  Platform:        ${PLATFORM}"
log "  Variant:         ${VARIANT}"
log "  Native targets:  ${NATIVE_TARGETS}"
log "  DL4J root:       ${DL4J_PROJECT_ROOT:-<not set — will clone>}"
log "  DL4J branch:     ${DL4J_BRANCH}"
log "  Kompile branch:  ${KOMPILE_BRANCH}"
log "  GraalVM:         ${GRAALVM_HOME:-<not found>}"
log "  Skip DL4J:       ${SKIP_DL4J}"
log "  Skip Java:       ${SKIP_JAVA}"
log "  Skip Native:     ${SKIP_NATIVE}"
log "  Skip Dist:       ${SKIP_DIST}"

kompile_build_for_platform "${PLATFORM}" "${SKIP_DL4J}" "${SKIP_JAVA}" "${SKIP_NATIVE}" "${SKIP_DIST}"
rc=$?

# Report SDX artifacts if present
sdx_dir="${KOMPILE_SDX_OUTPUT_DIR:-${KOMPILE_OUTPUT_DIR}/sdx-sdk}/${PLATFORM}"
if [ -d "${sdx_dir}" ] && [ -n "$(ls -A "${sdx_dir}" 2>/dev/null)" ]; then
  log "SDX artifacts for ${PLATFORM}:"
  find "${sdx_dir}" -maxdepth 1 \( -name '*.zip' -o -name '*.aar' \) -exec basename {} \; 2>/dev/null | sort | while read -r f; do
    log "  ${f}"
  done
  log "SDX output: ${sdx_dir}"
fi

exit "${rc}"
