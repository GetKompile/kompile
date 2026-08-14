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
#   ./build-kompile-platform.sh linux-x86_64 \
#       --dl4j-repository file:///srv/maven --nd4j-version 1.0.0-SNAPSHOT
#   ./build-kompile-platform.sh linux-x86_64 --publish \
#       --deploy-repository https://repo.example/snapshots
#
# Options:
#   --native-targets T   Comma-separated native image targets (default: cli)
#                        Valid: cli, component-cli, app, chat, crawl-manager,
#                        sample, app-lite, staging, model-serving, pipeline-serving,
#                        ingest, vector, embedding, model-init, vlm-test, training, or all
#   --variant V          Distribution variant (default: auto-detect from platform)
#                        Valid: cli-only, local, hosted, cpu-intel, cpu-arm, cuda, amd-zluda
#   --dl4j-branch B      DL4J branch to clone/checkout (default: master)
#   --kompile-branch B   Branch used only when cloning a missing Kompile checkout (default: main)
#   --dl4j-root DIR      Path to DL4J checkout (default: ../deeplearning4j, cloned if missing)
#   --dl4j-repository U  Resolve DL4J from Maven repository U; never build it
#   --dl4j-sdk-assets D  Extracted DL4J sdk-assets shard required with repository backends
#   --repository-id ID   Maven settings.xml server id for DL4J (default: dl4j-release)
#   --nd4j-version V     Published DL4J/ND4J version (default: 1.0.0-SNAPSHOT)
#   --version V          Kompile distribution/Maven version (default: root POM)
#   --maven-repo-local D Isolated Maven local repository
#   --publish            Deploy Kompile reactor artifacts after building
#   --deploy-repository U  Publish target (defaults to --dl4j-repository)
#   --deploy-repository-id ID  Maven settings.xml server id for publication
#   --skip-dl4j          Skip DL4J source build (use Maven local repository)
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
#   DL4J_SDX_ASSETS_DIR - extracted DL4J SDK shard (runtime packages plus jars/)
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
    --dl4j-repository)  DL4J_MAVEN_REPOSITORY_URL="$2"; SKIP_DL4J=1; shift 2 ;;
    --dl4j-sdk-assets)  DL4J_SDX_ASSETS_DIR="$2"; shift 2 ;;
    --repository-id)    DL4J_MAVEN_REPOSITORY_ID="$2"; shift 2 ;;
    --nd4j-version)     ND4J_VERSION="$2"; shift 2 ;;
    --version)          KOMPILE_VERSION="$2"; shift 2 ;;
    --maven-repo-local) MAVEN_REPO_LOCAL="$2"; shift 2 ;;
    --publish)          KOMPILE_PUBLISH=1; shift ;;
    --deploy-repository) KOMPILE_DEPLOY_REPOSITORY_URL="$2"; shift 2 ;;
    --deploy-repository-id) KOMPILE_DEPLOY_REPOSITORY_ID="$2"; shift 2 ;;
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
      head -41 "$0" | tail -39
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
kompile_validate_platform "${PLATFORM}" || exit 1

# Auto-detect variant from platform if not specified
if [ -z "${VARIANT:-}" ]; then
  case "${PLATFORM}" in
    *zluda*) VARIANT="amd-zluda" ;;
    *cuda*)  VARIANT="cuda" ;;
    *arm64*) VARIANT="cpu-arm" ;;
    *)       VARIANT="cpu-intel" ;;
  esac
fi

# Auto-provision if requested. The installer playbook lives in DL4J's shared
# build-common; load it even in repository/--skip-dl4j lanes so --setup never
# degrades into a silent no-op on a fresh host.
if [ "${DO_SETUP}" -eq 1 ]; then
  if [ "${_DL4J_COMMON_LOADED}" -ne 1 ]; then
    kompile_ensure_dl4j
    if [ -f "${DL4J_PROJECT_ROOT}/build-scripts/build-common.sh" ]; then
      PROJECT_ROOT="${DL4J_PROJECT_ROOT}" source "${DL4J_PROJECT_ROOT}/build-scripts/build-common.sh"
      _DL4J_COMMON_LOADED=1
    fi
  fi
  if [ "${_DL4J_COMMON_LOADED}" -ne 1 ]; then
    echo "Unable to load the DL4J host setup playbook." >&2
    exit 1
  fi
  auto_setup_host "${PLATFORM}"
fi

log "Kompile platform build"
log "  Platform:        ${PLATFORM}"
log "  Variant:         ${VARIANT}"
log "  Native targets:  ${NATIVE_TARGETS}"
log "  DL4J source:     ${DL4J_MAVEN_REPOSITORY_URL:-source checkout}"
log "  DL4J version:    ${ND4J_VERSION}"
log "  DL4J SDK assets: ${DL4J_SDX_ASSETS_DIR:-source build}"
log "  DL4J root:       ${DL4J_PROJECT_ROOT:-<not set — will clone in source mode>}"
log "  DL4J branch:     ${DL4J_BRANCH}"
log "  Publish Maven:   ${KOMPILE_PUBLISH}"
log "  Publish target:  ${KOMPILE_DEPLOY_REPOSITORY_URL:-${DL4J_MAVEN_REPOSITORY_URL:-<not configured>}}"
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
