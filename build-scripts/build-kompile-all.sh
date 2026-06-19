#!/usr/bin/env bash
###############################################################################
# build-kompile-all.sh
#
# Multi-platform build orchestrator for kompile.
# Builds DL4J backends + kompile native images for each requested platform.
#
# Usage:
#   ./build-kompile-all.sh                                    # Build for host
#   ./build-kompile-all.sh --platforms "linux-x86_64 linux-x86_64-cuda-12.9"
#   ./build-kompile-all.sh --platforms cuda                   # All CUDA targets
#   ./build-kompile-all.sh --platforms cpu                    # All CPU targets
#   ./build-kompile-all.sh --skip-dl4j                       # Pre-built nd4j
#   ./build-kompile-all.sh --native-targets cli,app,staging   # Full dist
#   ./build-kompile-all.sh --setup --platforms cuda           # Auto-provision
#
# Platform groups (same as DL4J):
#   cpu, cuda, rocm, linux, macos, windows, all
#
# Environment:
#   DL4J_PROJECT_ROOT  - path to deeplearning4j checkout
#   GRAALVM_HOME       - GraalVM installation path
#   MVN                - path to mvn binary
#   BUILD_THREADS      - parallel compilation threads
###############################################################################
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Parse CLI args before sourcing
PLATFORM_ARGS=()
DO_SETUP=0
SKIP_DL4J=0
SKIP_JAVA=0
SKIP_NATIVE=0
SKIP_DIST=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platforms)         IFS=' ' read -ra PLATFORM_ARGS <<< "$2"; shift 2 ;;
    --native-targets)    NATIVE_TARGETS="$2"; shift 2 ;;
    --variant)           VARIANT="$2"; shift 2 ;;
    --threads)           BUILD_THREADS="$2"; shift 2 ;;
    --output-dir)        KOMPILE_OUTPUT_DIR="$2"; shift 2 ;;
    --dl4j-root)         DL4J_PROJECT_ROOT="$2"; shift 2 ;;
    --dl4j-branch)       DL4J_BRANCH="$2"; shift 2 ;;
    --kompile-branch)    KOMPILE_BRANCH="$2"; shift 2 ;;
    --graalvm-home)      GRAALVM_HOME="$2"; shift 2 ;;
    --mvn)               MVN="$2"; shift 2 ;;
    --skip-dl4j)         SKIP_DL4J=1; shift ;;
    --skip-java)         SKIP_JAVA=1; shift ;;
    --skip-native)       SKIP_NATIVE=1; shift ;;
    --skip-dist)         SKIP_DIST=1; shift ;;
    --setup)             DO_SETUP=1; shift ;;
    --list|-l)
      source "${SCRIPT_DIR}/build-common.sh"
      echo "Kompile build platforms:"
      printf '  %s\n' "${KOMPILE_PLATFORMS[@]}"
      exit 0 ;;
    --help|-h)
      head -30 "$0" | tail -28
      exit 0 ;;
    *)
      echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# Source shared build library
source "${SCRIPT_DIR}/build-common.sh"

# ─── Resolve platform list ─────────────────────────────────────────────────
SELECTED_PLATFORMS=()
if [ ${#PLATFORM_ARGS[@]} -eq 0 ]; then
  # Default: auto-detect what this host can build from kompile's list
  local_platform="$(kompile_detect_platform)"
  case "${local_platform}" in
    linux-x86_64)  SELECTED_PLATFORMS=("linux-x86_64") ;;
    linux-arm64)   SELECTED_PLATFORMS=("linux-arm64") ;;
    macosx-arm64)  SELECTED_PLATFORMS=("macosx-arm64") ;;
    windows-*)     SELECTED_PLATFORMS=("windows-x86_64") ;;
    *)             SELECTED_PLATFORMS=("linux-x86_64") ;;
  esac
else
  for arg in "${PLATFORM_ARGS[@]}"; do
    case "${arg}" in
      cpu)
        for p in "${KOMPILE_PLATFORMS[@]}"; do
          [[ "$p" != *cuda* ]] && [[ "$p" != *rocm* ]] && SELECTED_PLATFORMS+=("$p")
        done ;;
      cuda)
        for p in "${KOMPILE_PLATFORMS[@]}"; do
          [[ "$p" == *cuda* ]] && SELECTED_PLATFORMS+=("$p")
        done ;;
      rocm)
        for p in "${KOMPILE_PLATFORMS[@]}"; do
          [[ "$p" == *rocm* ]] && SELECTED_PLATFORMS+=("$p")
        done ;;
      linux)
        for p in "${KOMPILE_PLATFORMS[@]}"; do
          [[ "$p" == linux-* ]] && SELECTED_PLATFORMS+=("$p")
        done ;;
      macos|mac)
        for p in "${KOMPILE_PLATFORMS[@]}"; do
          [[ "$p" == macosx-* ]] && SELECTED_PLATFORMS+=("$p")
        done ;;
      windows|win)
        for p in "${KOMPILE_PLATFORMS[@]}"; do
          [[ "$p" == windows-* ]] && SELECTED_PLATFORMS+=("$p")
        done ;;
      all)
        SELECTED_PLATFORMS=("${KOMPILE_PLATFORMS[@]}") ;;
      *)
        # Literal platform name
        SELECTED_PLATFORMS+=("${arg}") ;;
    esac
  done
fi

if [ ${#SELECTED_PLATFORMS[@]} -eq 0 ]; then
  log "ERROR: No platforms selected"
  exit 1
fi

# ─── Optional: auto-provision ──────────────────────────────────────────────
if [ "${DO_SETUP}" -eq 1 ] && [ "${_DL4J_COMMON_LOADED}" -eq 1 ]; then
  auto_setup_host "${SELECTED_PLATFORMS[@]}"
fi

# ─── Main execution ────────────────────────────────────────────────────────

main() {
  log "Kompile multi-platform build"
  log "  Platforms (${#SELECTED_PLATFORMS[@]}): ${SELECTED_PLATFORMS[*]}"
  log "  Native targets: ${NATIVE_TARGETS}"
  log "  Variant: ${VARIANT:-auto}"
  log "  DL4J root: ${DL4J_PROJECT_ROOT:-<not set — will clone>}"
  log "  DL4J branch: ${DL4J_BRANCH}"
  log "  Kompile branch: ${KOMPILE_BRANCH}"
  log "  GraalVM: ${GRAALVM_HOME:-<not found>}"
  log "  Threads: ${BUILD_THREADS}"
  log "  Output: ${KOMPILE_OUTPUT_DIR}"

  mkdir -p "${KOMPILE_OUTPUT_DIR}"
  {
    echo "Kompile build started: $(date)"
    echo "Platforms: ${SELECTED_PLATFORMS[*]}"
    echo "---"
  } > "${KOMPILE_OUTPUT_DIR}/build-summary.txt"

  local failed=0
  local succeeded=0
  local total=${#SELECTED_PLATFORMS[@]}

  for platform in "${SELECTED_PLATFORMS[@]}"; do
    log "Building ${platform} ($(( succeeded + failed + 1 ))/${total})"

    # Auto-detect variant per platform
    local effective_variant="${VARIANT:-}"
    if [ -z "${effective_variant}" ]; then
      case "${platform}" in
        *cuda*)  effective_variant="cuda" ;;
        *rocm*)  effective_variant="amd-zluda" ;;
        *arm64*) effective_variant="cpu-arm" ;;
        *)       effective_variant="cpu-intel" ;;
      esac
    fi
    VARIANT="${effective_variant}"

    if kompile_build_for_platform "${platform}" "${SKIP_DL4J}" "${SKIP_JAVA}" "${SKIP_NATIVE}" "${SKIP_DIST}"; then
      succeeded=$(( succeeded + 1 ))
      echo "${platform}: SUCCESS" >> "${KOMPILE_OUTPUT_DIR}/build-summary.txt"
    else
      failed=$(( failed + 1 ))
      echo "${platform}: FAILED" >> "${KOMPILE_OUTPUT_DIR}/build-summary.txt"
      log "FAILED: ${platform} — continuing with next platform"
    fi
  done

  # Report SDX artifacts
  local sdx_dir="${KOMPILE_SDX_OUTPUT_DIR:-${KOMPILE_OUTPUT_DIR}/sdx-sdk}"
  local sdx_count=0
  if [ -d "${sdx_dir}" ]; then
    sdx_count=$(find "${sdx_dir}" -type f \( -name '*.zip' -o -name '*.aar' \) 2>/dev/null | wc -l)
  fi

  {
    echo "---"
    echo "Total: ${total}, Succeeded: ${succeeded}, Failed: ${failed}"
    echo "SDX artifacts: ${sdx_count} (${sdx_dir})"
    echo "Build finished: $(date)"
  } >> "${KOMPILE_OUTPUT_DIR}/build-summary.txt"

  log "BUILD SUMMARY: ${succeeded}/${total} succeeded, ${failed} failed"
  if [ "${sdx_count}" -gt 0 ]; then
    log "SDX artifacts: ${sdx_count} file(s) in ${sdx_dir}"
    find "${sdx_dir}" -type f \( -name '*.zip' -o -name '*.aar' \) -exec basename {} \; 2>/dev/null | sort | while read -r f; do
      log "  ${f}"
    done
  fi
  cat "${KOMPILE_OUTPUT_DIR}/build-summary.txt"

  [ "$failed" -eq 0 ]
}

main
