#!/usr/bin/env bash
###############################################################################
# build-common.sh
#
# Shared library for kompile build scripts.
# Source this file — do NOT execute it directly.
#
#   source build-scripts/build-common.sh
#
# Provides:
#   - DL4J build-common re-export (if DL4J_PROJECT_ROOT is set)
#   - Kompile-specific configuration (GRAALVM_HOME, native targets, etc.)
#   - kompile_build_java_modules()     — Maven install for kompile
#   - kompile_build_native_image()     — Build a single native image
#   - kompile_build_all_native()       — Build all native image targets
#   - kompile_assemble_dist()          — Package distribution tarball
#   - kompile_build_for_platform()     — End-to-end: DL4J backend + kompile
#
# Variables (set before sourcing or accept defaults):
#   DL4J_PROJECT_ROOT  — path to deeplearning4j checkout (../deeplearning4j)
#   KOMPILE_ROOT       — path to kompile checkout (auto-detected)
#   GRAALVM_HOME       — GraalVM installation
#   MVN                — path to mvn binary
#   BUILD_THREADS      — parallel compilation threads
#   NATIVE_TARGETS     — comma-separated: cli,app,staging (default: cli)
#   VARIANT            — distribution variant (cli-only, hosted, cpu-intel, etc.)
###############################################################################

# Guard against double-sourcing
if [ "${_KOMPILE_BUILD_COMMON_LOADED:-}" = "1" ]; then
  return 0 2>/dev/null || true
fi
_KOMPILE_BUILD_COMMON_LOADED=1

# ─── Locate project roots ──────────────────────────────────────────────────
if [ -z "${KOMPILE_ROOT:-}" ]; then
  _KOMPILE_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  KOMPILE_ROOT="$(cd "${_KOMPILE_COMMON_DIR}/.." && pwd)"
fi

# Branch defaults (override with env vars or CLI flags)
DL4J_BRANCH="${DL4J_BRANCH:-master}"
KOMPILE_BRANCH="${KOMPILE_BRANCH:-main}"

# Repo URLs
DL4J_REPO_URL="${DL4J_REPO_URL:-https://github.com/deeplearning4j/deeplearning4j.git}"
KOMPILE_REPO_URL="${KOMPILE_REPO_URL:-https://github.com/GetKompile/kompile.git}"

DL4J_PROJECT_ROOT="${DL4J_PROJECT_ROOT:-$(cd "${KOMPILE_ROOT}/../deeplearning4j" 2>/dev/null && pwd || echo "")}"

# ─── Minimal stubs (defined early so ensure_repo can use log) ──────────────
# These get overwritten if DL4J build-common loads successfully.
if ! type log &>/dev/null; then
  log() { echo "=== [$(date '+%Y-%m-%d %H:%M:%S')] $* ==="; }
fi
if ! type detect_host_type &>/dev/null; then
  detect_host_type() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"
    case "${os}" in
      Linux)
        case "${arch}" in
          x86_64)  echo "linux-x86_64" ;;
          aarch64) echo "linux-arm64" ;;
          *)       echo "linux-${arch}" ;;
        esac ;;
      Darwin)  echo "macos" ;;
      MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
      *)       echo "unknown" ;;
    esac
  }
fi

# ─── Repo clone / checkout ─────────────────────────────────────────────────
# Clone a repo if the directory doesn't exist, then checkout the branch.
# If the directory already exists, just checkout the requested branch.
#
# Usage: kompile_ensure_repo <dir> <repo_url> <branch>
kompile_ensure_repo() {
  local dir="$1"
  local url="$2"
  local branch="$3"

  if [ ! -d "${dir}" ]; then
    log "Cloning ${url} (branch ${branch}) into ${dir}"
    git clone --branch "${branch}" --depth 1 "${url}" "${dir}"
  else
    local current_branch
    current_branch="$(git -C "${dir}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
    if [ "${current_branch}" != "${branch}" ]; then
      log "Checking out branch ${branch} in ${dir} (was ${current_branch})"
      git -C "${dir}" fetch origin "${branch}" --depth 1 2>/dev/null || git -C "${dir}" fetch origin "${branch}"
      git -C "${dir}" checkout "${branch}"
    fi
  fi
}

# Ensure DL4J repo is present and on the right branch.
kompile_ensure_dl4j() {
  local target="${DL4J_PROJECT_ROOT:-${KOMPILE_ROOT}/../deeplearning4j}"
  kompile_ensure_repo "${target}" "${DL4J_REPO_URL}" "${DL4J_BRANCH}"
  DL4J_PROJECT_ROOT="$(cd "${target}" && pwd)"
  export DL4J_PROJECT_ROOT
}

# Ensure kompile repo is on the right branch (only useful in CI / fresh clones).
kompile_ensure_kompile() {
  kompile_ensure_repo "${KOMPILE_ROOT}" "${KOMPILE_REPO_URL}" "${KOMPILE_BRANCH}"
}

# ─── Source DL4J build-common if available ──────────────────────────────────
if [ -n "${DL4J_PROJECT_ROOT}" ] && [ -f "${DL4J_PROJECT_ROOT}/build-scripts/build-common.sh" ]; then
  # DL4J's build-common uses PROJECT_ROOT — point it at DL4J, not kompile
  PROJECT_ROOT="${DL4J_PROJECT_ROOT}" source "${DL4J_PROJECT_ROOT}/build-scripts/build-common.sh"
  _DL4J_COMMON_LOADED=1
else
  _DL4J_COMMON_LOADED=0
fi


# ═══════════════════════════════════════════════════════════════════════════════
# 1. KOMPILE CONFIGURATION
# ═══════════════════════════════════════════════════════════════════════════════

MVN="${MVN:-/home/agibsonccc/dev-apps/mvn/bin/mvn}"
BUILD_THREADS="${BUILD_THREADS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 8)}"

# DL4J dependency source. When DL4J_MAVEN_REPOSITORY_URL is non-empty,
# Kompile resolves the complete ND4J/DL4J graph from that Maven 2 repository
# and never clones or compiles DL4J. Credentials are supplied through a Maven
# settings.xml <server> whose id matches DL4J_MAVEN_REPOSITORY_ID.
ND4J_VERSION="${ND4J_VERSION:-1.0.0-SNAPSHOT}"
DL4J_MAVEN_REPOSITORY_URL="${DL4J_MAVEN_REPOSITORY_URL:-}"
DL4J_MAVEN_REPOSITORY_ID="${DL4J_MAVEN_REPOSITORY_ID:-dl4j-release}"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-}"

# An explicit publish operation defaults to the same target that supplied DL4J.
KOMPILE_PUBLISH="${KOMPILE_PUBLISH:-0}"
KOMPILE_DEPLOY_REPOSITORY_URL="${KOMPILE_DEPLOY_REPOSITORY_URL:-}"
KOMPILE_DEPLOY_REPOSITORY_ID="${KOMPILE_DEPLOY_REPOSITORY_ID:-}"

# GraalVM — try several locations
if [ -z "${GRAALVM_HOME:-}" ]; then
  for _candidate in \
    "${HOME}/.sdkman/candidates/java/21.0.10-graal" \
    "${HOME}/.kompile/graalvm" \
    "${JAVA_HOME:-}"; do
    if [ -n "${_candidate}" ] && [ -x "${_candidate}/bin/native-image" ]; then
      GRAALVM_HOME="${_candidate}"
      break
    fi
  done
fi
GRAALVM_HOME="${GRAALVM_HOME:-}"

# Native image targets
# Native image targets (comma-separated)
# Valid targets:
#   cli            — kompile CLI (kompile-cli-main)
#   component-cli  — component query CLI (kompile-component-cli)
#   app            — RAG server (kompile-sample / generated project)
#   app-lite       — lightweight RAG server (kompile-app-lite)
#   staging        — model staging orchestrator (kompile-model-staging)
#   ingest         — document ingest subprocess (kompile-app-main -Pnative-ingest)
#   vector         — vector population subprocess (kompile-app-main -Pnative-vector)
#   embedding      — embedding subprocess (kompile-app-main -Pnative-embedding)
#   model-init     — model init subprocess (kompile-app-main -Pnative-model-init)
#   vlm-test       — VLM test subprocess (kompile-app-main -Pnative-vlm-test)
#   training       — training subprocess (kompile-app-main -Pnative-training)
#   all            — all of the above
NATIVE_TARGETS="${NATIVE_TARGETS:-all}"

# Distribution variant. Entry points resolve an unset value from the requested
# backend/platform; forcing cli-only here previously defeated that auto-detection.
VARIANT="${VARIANT:-}"

# Output directory
KOMPILE_OUTPUT_DIR="${KOMPILE_OUTPUT_DIR:-${KOMPILE_ROOT}/dist}"

# SDX bindings output — mirrors DL4J's SDX_OUTPUT_DIR, collected into kompile dist
KOMPILE_SDX_OUTPUT_DIR="${KOMPILE_SDX_OUTPUT_DIR:-${KOMPILE_OUTPUT_DIR}/sdx-sdk}"
# Repository-only builds cannot derive the non-Maven runtime SDK packages from
# DL4J JARs. Point this at an extracted DL4J sdk-assets shard (or a root with
# one subdirectory per platform).
DL4J_SDX_ASSETS_DIR="${DL4J_SDX_ASSETS_DIR:-}"

# Maven flags for Kompile Java builds. Keep these as an array so repository
# URLs and local repository paths are never reparsed by the shell.
KOMPILE_MVN_ARGS=(--batch-mode --no-transfer-progress -Dmaven.test.skip=true)

# Classifiers attested by ../deeplearning4j/release. Keep this list closed:
# requesting a missing classifier must fail instead of silently using a base JAR.
KOMPILE_PLATFORMS=(
  "linux-x86_64"
  "linux-x86_64-avx2"
  "linux-x86_64-avx512"
  "linux-x86_64-onednn"
  "linux-x86_64-onednn-avx2"
  "linux-x86_64-onednn-avx512"
  "linux-x86_64-compile"
  "linux-x86_64-compat"
  "linux-arm64"
  "linux-arm64-armcompute"
  "linux-arm64-onednn"
  "linux-arm64-compile"
  "android-arm64"
  "android-arm64-armcompute"
  "android-arm64-nnapi"
  "android-arm64-compile"
  "android-arm64-compile-nnapi"
  "android-x86_64"
  "android-x86_64-onednn"
  "android-x86_64-compile"
  "macosx-arm64"
  "macosx-arm64-compile"
  "macosx-arm64-mps"
  "macosx-arm64-mps-compile"
  "windows-x86_64"
  "windows-x86_64-avx2"
  "windows-x86_64-avx512"
  "windows-x86_64-onednn"
  "windows-x86_64-onednn-avx2"
  "windows-x86_64-onednn-avx512"
  "linux-x86_64-cuda-12.6"
  "linux-x86_64-cuda-12.6-cudnn"
  "linux-x86_64-cuda-12.6-compile"
  "linux-x86_64-cuda-12.9"
  "linux-x86_64-cuda-12.9-cudnn"
  "linux-x86_64-cuda-12.9-compile"
  "windows-x86_64-cuda-12.6"
  "windows-x86_64-cuda-12.6-cudnn"
  "windows-x86_64-cuda-12.9"
  "windows-x86_64-cuda-12.9-cudnn"
  "linux-x86_64-cuda-12.9-zluda"
  "windows-x86_64-cuda-12.9-zluda"
  "linux-x86_64-vulkan"
  "linux-x86_64-vulkan-compile"
  "linux-x86_64-hexagon"
  "linux-x86_64-tpu"
)


# ═══════════════════════════════════════════════════════════════════════════════
# 2. UTILITY FUNCTIONS
# ═══════════════════════════════════════════════════════════════════════════════

# Detect the host platform string for kompile's naming.
kompile_detect_platform() {
  local os arch
  case "$(uname -s)" in
    Linux*)  os="linux" ;;
    Darwin*) os="macosx" ;;
    *)       os="windows" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64)   arch="x86_64" ;;
    aarch64|arm64)  arch="arm64" ;;
    *)              arch="$(uname -m)" ;;
  esac
  echo "${os}-${arch}"
}

kompile_validate_platform() {
  local requested="$1" candidate
  for candidate in "${KOMPILE_PLATFORMS[@]}"; do
    if [ "${requested}" = "${candidate}" ]; then
      return 0
    fi
  done
  log "ERROR: unsupported DL4J release classifier '${requested}'"
  log "Run build-kompile-platform.sh --list for the attested classifier matrix."
  return 1
}

# Check that GraalVM is available and has native-image
kompile_check_graalvm() {
  if [ -z "${GRAALVM_HOME}" ] || [ ! -x "${GRAALVM_HOME}/bin/native-image" ]; then
    log "ERROR: GraalVM not found or missing native-image"
    log "Set GRAALVM_HOME or install: sdk install java 21.0.10-graal"
    return 1
  fi
  log "GraalVM: ${GRAALVM_HOME}"
  return 0
}

# Resolve the public Kompile backend alias from an attested classifier.
_resolve_backend_from_platform() {
  local platform="$1"
  case "$platform" in
    *cuda-12.9-zluda)  echo "cuda" "12.9" "zluda" ;;
    *cuda-12.9-cudnn)  echo "cuda" "12.9" "cuda-12.9-cudnn" ;;
    *cuda-12.9-compile) echo "cuda" "12.9" "cuda-12.9-compile" ;;
    *cuda-12.9)        echo "cuda" "12.9" "cuda-12.9" ;;
    *cuda-12.6-cudnn)  echo "cuda" "12.6" "cuda-12.6-cudnn" ;;
    *cuda-12.6-compile) echo "cuda" "12.6" "cuda-12.6-compile" ;;
    *cuda-12.6)        echo "cuda" "12.6" "cuda-12.6" ;;
    *vulkan-compile)   echo "vulkan" "" "vulkan-compile" ;;
    *vulkan)           echo "vulkan" "" "vulkan" ;;
    *hexagon)          echo "hexagon" "" "hexagon" ;;
    *tpu)              echo "tpu" "" "tpu" ;;
    *onednn-avx512)    echo "cpu" "" "cpu-onednn-avx512" ;;
    *onednn-avx2)      echo "cpu" "" "cpu-onednn-avx2" ;;
    *onednn)           echo "cpu" "" "cpu-onednn" ;;
    *avx512)           echo "cpu" "" "cpu-avx512" ;;
    *avx2)             echo "cpu" "" "cpu-avx2" ;;
    *armcompute)       echo "cpu" "" "cpu-armcompute" ;;
    *mps-compile)      echo "cpu" "" "cpu-mps-compile" ;;
    *mps)              echo "cpu" "" "cpu-mps" ;;
    *compile-nnapi)    echo "cpu" "" "cpu-compile-nnapi" ;;
    *nnapi)            echo "cpu" "" "cpu-nnapi" ;;
    *compat)           echo "cpu" "" "cpu-compat" ;;
    *compile)          echo "cpu" "" "cpu-compile" ;;
    *)                 echo "cpu" "" "cpu" ;;
  esac
}

_resolve_javacpp_platform() {
  case "$1" in
    linux-x86_64*) echo "linux-x86_64" ;;
    linux-arm64*) echo "linux-arm64" ;;
    macosx-arm64*) echo "macosx-arm64" ;;
    windows-x86_64*) echo "windows-x86_64" ;;
    android-arm64*) echo "android-arm64" ;;
    android-x86_64*) echo "android-x86_64" ;;
    *) log "ERROR: unsupported release classifier '$1'"; return 1 ;;
  esac
}

# The SDK archive classifier is not always the JavaCPP base platform. CPU helper
# lanes retain their full release identity; accelerator helpers are represented
# as suffixes on their base JavaCPP platform.
_resolve_sdk_classifier() {
  local platform="$1" base
  base="$(_resolve_javacpp_platform "${platform}")" || return 1
  case "${platform}" in
    *cuda*-cudnn) echo "${base}-cudnn" ;;
    *cuda*-compile) echo "${base}-compile" ;;
    *cuda*-zluda) echo "${base}-zluda" ;;
    *cuda*) echo "${base}" ;;
    *) echo "${platform}" ;;
  esac
}

_kompile_lane_requires_runtime() {
  case "$1" in
    *-compat|*vulkan*|*hexagon*|*tpu*|*zluda*) return 1 ;;
    linux-x86_64*|linux-arm64*|macosx-arm64*|windows-x86_64*|android-*) return 0 ;;
    *) return 1 ;;
  esac
}

kompile_prepare_dependency_maven_args() {
  KOMPILE_DEPENDENCY_MAVEN_ARGS=("-Dnd4j.version=${ND4J_VERSION}")
  if [ -n "${MAVEN_REPO_LOCAL}" ]; then
    KOMPILE_DEPENDENCY_MAVEN_ARGS+=("-Dmaven.repo.local=${MAVEN_REPO_LOCAL}")
  fi
  if [ -n "${DL4J_MAVEN_REPOSITORY_URL}" ]; then
    KOMPILE_DEPENDENCY_MAVEN_ARGS+=(
      "-Ddl4j.repository.id=${DL4J_MAVEN_REPOSITORY_ID}"
      "-Ddl4j.repository.url=${DL4J_MAVEN_REPOSITORY_URL}"
    )
  fi
}

kompile_prepare_deploy_maven_args() {
  local repository_url="${KOMPILE_DEPLOY_REPOSITORY_URL:-${DL4J_MAVEN_REPOSITORY_URL}}"
  local repository_id="${KOMPILE_DEPLOY_REPOSITORY_ID:-${DL4J_MAVEN_REPOSITORY_ID}}"
  if [ -z "${repository_url}" ]; then
    log "ERROR: publishing requires KOMPILE_DEPLOY_REPOSITORY_URL or DL4J_MAVEN_REPOSITORY_URL"
    return 1
  fi
  KOMPILE_DEPLOY_MAVEN_ARGS=("-DaltDeploymentRepository=${repository_id}::${repository_url}")
}


# ═══════════════════════════════════════════════════════════════════════════════
# 3. DL4J BACKEND BUILD (delegates to DL4J build-common)
# ═══════════════════════════════════════════════════════════════════════════════

# Build the DL4J backend for a given platform.
# Requires DL4J_PROJECT_ROOT to be set and build-common.sh loaded.
kompile_build_dl4j_backend() {
  local platform="$1"

  if [ "${_DL4J_COMMON_LOADED}" -ne 1 ]; then
    log "ERROR: DL4J build-common not loaded. Set DL4J_PROJECT_ROOT."
    return 1
  fi

  log "Building DL4J backend for ${platform}"
  cd "${DL4J_PROJECT_ROOT}"
  build_platform "$platform"
  cd "${KOMPILE_ROOT}"
}


# ═══════════════════════════════════════════════════════════════════════════════
# 3b. SDX BINDINGS COLLECTION
# ═══════════════════════════════════════════════════════════════════════════════

# Collect the complete SDK shard produced by a DL4J source build: runtime
# packages plus the platform-classified Maven JARs needed by native SDK users.
#
# Usage: kompile_collect_sdx_bindings <platform>
kompile_collect_sdx_bindings() {
  local platform="$1" sdk_classifier javacpp_platform runtime_required=0
  javacpp_platform="$(_resolve_javacpp_platform "${platform}")" || return 1
  sdk_classifier="$(_resolve_sdk_classifier "${platform}")" || return 1
  _kompile_lane_requires_runtime "${platform}" && runtime_required=1

  # DL4J's SDX output dir (set by DL4J build-common.sh when sourced)
  local dl4j_sdx_dir="${DL4J_PROJECT_ROOT}/build-output/sdx-sdk/${platform}"

  # Also check the blasbuild dist dir directly in case package_sdx_bindings
  # wasn't called or SDX_OUTPUT_DIR was overridden
  local blasbuild_dir
  case "${platform}" in
    *cuda*)         blasbuild_dir="${DL4J_PROJECT_ROOT}/libnd4j/blasbuild/cuda" ;;
    *rocm*|*zluda*) blasbuild_dir="${DL4J_PROJECT_ROOT}/libnd4j/blasbuild/cuda" ;;
    *)              blasbuild_dir="${DL4J_PROJECT_ROOT}/libnd4j/blasbuild/cpu" ;;
  esac
  local blasbuild_dist="${blasbuild_dir}/sdx-runtime-sdk/dist"

  # Maven-only lanes deliberately have no SDK runtime source tree.
  local src_dir=""
  if [ "${runtime_required}" -eq 1 ] && [ -d "${dl4j_sdx_dir}" ] && [ -n "$(ls -A "${dl4j_sdx_dir}" 2>/dev/null)" ]; then
    src_dir="${dl4j_sdx_dir}"
  elif [ "${runtime_required}" -eq 1 ] && [ -d "${blasbuild_dist}" ] && [ -n "$(ls -A "${blasbuild_dist}" 2>/dev/null)" ]; then
    src_dir="${blasbuild_dist}"
  elif [ "${runtime_required}" -eq 1 ]; then
    log "ERROR: SDX runtime packages were not produced for ${platform}"
    return 1
  fi

  local dest="${KOMPILE_SDX_OUTPUT_DIR}/${platform}"
  rm -rf "${dest}"
  mkdir -p "${dest}"
  if [ -n "${src_dir}" ]; then
    cp -a "${src_dir}/." "${dest}/"
  fi

  # Preserve binding descriptors even when build_platform wrote its packages
  # from the dist directory rather than its bindings tree.
  while IFS= read -r -d '' f; do
    local rel_dir
    rel_dir="$(dirname "${f}")"
    rel_dir="${rel_dir##*/}"  # variant name (cpu, cuda, etc.)
    mkdir -p "${dest}/${rel_dir}"
    cp -p "${f}" "${dest}/${rel_dir}/"
  done < <(find "${blasbuild_dir}/sdx-runtime-sdk/bindings" -name "binding.json" -print0 2>/dev/null)

  # Match the DL4J release driver's package_sdk_jars output: keep unclassified
  # API/platform JARs for the lane and only the requested platform classifier.
  local maven_repository
  maven_repository="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}"
  mkdir -p "${dest}/jars"
  local -a sdk_artifact_ids
  case "${platform}" in
    *zluda*)
      if [[ "${platform}" == windows-* ]]; then
        sdk_artifact_ids=(nd4j-cuda-12.9 nd4j-cuda-12.9-preset)
      else
        sdk_artifact_ids=(nd4j-cuda-12.9 nd4j-cuda-12.9-preset nd4j-zluda nd4j-zluda-platform)
      fi
      ;;
    *cuda-12.6*)
      sdk_artifact_ids=(nd4j-cuda-12.6 nd4j-cuda-12.6-preset nd4j-cuda-12.6-platform)
      ;;
    *cuda*)
      sdk_artifact_ids=(nd4j-cuda-12.9 nd4j-cuda-12.9-preset nd4j-cuda-12.9-platform)
      ;;
    android-*|*compat*) sdk_artifact_ids=(nd4j-native nd4j-native-preset) ;;
    *vulkan*) sdk_artifact_ids=(nd4j-vulkan nd4j-vulkan-preset) ;;
    *hexagon*) sdk_artifact_ids=(nd4j-hexagon nd4j-hexagon-preset) ;;
    *tpu*) sdk_artifact_ids=(nd4j-tpu nd4j-tpu-preset) ;;
    linux-x86_64*|windows-x86_64*)
      sdk_artifact_ids=(nd4j-native nd4j-native-preset nd4j-native-platform libtokenizers tokenizers-native-preset tokenizers-native)
      ;;
    linux-arm64*|macosx-arm64*)
      sdk_artifact_ids=(nd4j-native nd4j-native-preset libtokenizers tokenizers-native-preset tokenizers-native)
      ;;
    *) log "ERROR: no release-plan artifact set for ${platform}"; return 1 ;;
  esac
  local namespace artifact_id artifact_dir jar_name f
  for namespace in org/eclipse/deeplearning4j org/nd4j; do
    for artifact_id in "${sdk_artifact_ids[@]}"; do
      artifact_dir="${maven_repository}/${namespace}/${artifact_id}/${ND4J_VERSION}"
      [ -d "${artifact_dir}" ] || continue
      while IFS= read -r -d '' f; do
        jar_name="$(basename "${f}")"
        case "${jar_name}" in
          *-sources.jar|*-javadoc.jar|*-tests.jar) continue ;;
        esac
        if [[ "${jar_name}" =~ (linux-|windows-|macosx-|android-|ios-) ]] \
            && [[ "${jar_name}" != *-"${sdk_classifier}".jar ]]; then
          continue
        fi
        cp -p "${f}" "${dest}/jars/"
      done < <(find "${artifact_dir}" -type f -name '*.jar' -print0 2>/dev/null)
    done
  done

  local runtime_count jar_count
  runtime_count=$(find "${dest}" -type f \( -name '*.zip' -o -name '*.aar' \) | wc -l)
  jar_count=$(find "${dest}/jars" -type f -name '*.jar' | wc -l)
  if [ "${jar_count}" -eq 0 ] || { [ "${runtime_required}" -eq 1 ] && [ "${runtime_count}" -eq 0 ]; }; then
    log "ERROR: incomplete DL4J SDK shard for ${platform}: runtime=${runtime_count}, jars=${jar_count}"
    return 1
  fi

  local backend_type lane_cuda_version backend_profile backend_artifact validation_cuda_version
  read -r backend_type lane_cuda_version backend_profile < <(_resolve_backend_from_platform "${platform}")
  case "${backend_profile}" in
    cpu*) backend_artifact=nd4j-native ;;
    cuda-12.6*) backend_artifact=nd4j-cuda-12.6 ;;
    cuda-12.9*) backend_artifact=nd4j-cuda-12.9 ;;
    zluda) backend_artifact=nd4j-zluda ;;
    vulkan*) backend_artifact=nd4j-vulkan ;;
    hexagon) backend_artifact=nd4j-hexagon ;;
    tpu) backend_artifact=nd4j-tpu ;;
    *) log "ERROR: unsupported backend profile for ${platform}: ${backend_profile}"; return 1 ;;
  esac
  validation_cuda_version="${lane_cuda_version:-12.9}"
  bash "${KOMPILE_ROOT}/kompile-dist/src/main/build/validate-sdx-assets.sh" \
    "${dest}" "${VARIANT}" "${javacpp_platform}" "${ND4J_VERSION}" \
    "${validation_cuda_version}" "${backend_artifact}" "${sdk_classifier}" || return 1
  log "Complete DL4J SDK shard collected: ${runtime_count} runtime package(s), ${jar_count} JAR(s) → ${dest}"
}


# ═══════════════════════════════════════════════════════════════════════════════
# 4. KOMPILE JAVA BUILD
# ═══════════════════════════════════════════════════════════════════════════════

# Install all Kompile Java modules. Publication is intentionally deferred until
# the distribution ZIP has also been installed in the same local repository.
kompile_build_java_modules() {
  local -a extra_args=("$@")
  log "Building kompile Java modules (goal: install)"
  cd "${KOMPILE_ROOT}"

  kompile_prepare_dependency_maven_args
  local -a cmd=(
    "${MVN}" clean install
    "${KOMPILE_MVN_ARGS[@]}"
    "${KOMPILE_DEPENDENCY_MAVEN_ARGS[@]}"
    "${extra_args[@]}"
  )
  log "Command: ${cmd[*]}"
  "${cmd[@]}" 2>&1 | tee "${KOMPILE_OUTPUT_DIR}/kompile-java-build.log"
  local rc=${PIPESTATUS[0]}
  if [ "${rc}" -ne 0 ]; then
    log "FAILED: kompile Java build (exit ${rc})"
    return 1
  fi
  log "kompile Java modules installed"
}


# ═══════════════════════════════════════════════════════════════════════════════
# 5. NATIVE IMAGE BUILDS
# ═══════════════════════════════════════════════════════════════════════════════

# Build a single native image target.
#   $1 = target name (see NATIVE_TARGETS comment above for valid values)
#   remaining arguments = extra Maven arguments (optional)
kompile_build_native_image() {
  local target="$1"
  shift
  local -a extra_args=("$@")

  kompile_check_graalvm || return 1

  local module_dir profile image_name log_file
  case "${target}" in
    # ── Standalone CLIs ────────────────────────────────────────────────
    cli)
      module_dir="${KOMPILE_ROOT}/kompile-cli"
      profile="native"
      image_name="kompile-cli-main"
      ;;
    component-cli)
      module_dir="${KOMPILE_ROOT}/kompile-cli/kompile-component-cli"
      profile="native"
      image_name="kompile-component"
      ;;
    # ── Application servers ────────────────────────────────────────────
    app)
      module_dir="${KOMPILE_ROOT}/kompile-rag-builds/kompile-sample/project"
      profile="native"
      image_name="kompile-sample-native"
      ;;
    app-lite)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-lite"
      profile="native"
      image_name="kompile-app-lite-native"
      ;;
    # ── Model staging orchestrator ─────────────────────────────────────
    staging)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-models/kompile-model-staging"
      profile="native"
      image_name="kompile-model-staging"
      ;;
    # ── Subprocess native images (built from kompile-app-main) ─────────
    ingest)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main"
      profile="native-ingest"
      image_name="kompile-ingest"
      ;;
    vector)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main"
      profile="native-vector"
      image_name="kompile-vector"
      ;;
    embedding)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main"
      profile="native-embedding"
      image_name="kompile-embedding"
      ;;
    model-init)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main"
      profile="native-model-init"
      image_name="kompile-model-init"
      ;;
    vlm-test)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main"
      profile="native-vlm-test"
      image_name="kompile-vlm-test"
      ;;
    training)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main"
      profile="native-training"
      image_name="kompile-training"
      ;;
    *)
      log "ERROR: Unknown native target '${target}'"
      log "Valid targets: cli, component-cli, app, app-lite, staging, ingest, vector, embedding, model-init, vlm-test, training"
      return 1
      ;;
  esac
  log_file="${KOMPILE_OUTPUT_DIR}/native-${target}.log"

  log "Building native image: ${target} (${image_name})"
  log "  Module: ${module_dir}"
  log "  Profile: ${profile}"
  log "  GraalVM: ${GRAALVM_HOME}"

  kompile_prepare_dependency_maven_args
  local -a cmd=(
    "${MVN}" package "-P${profile}" -DskipTests
    "${KOMPILE_DEPENDENCY_MAVEN_ARGS[@]}"
    "${extra_args[@]}"
  )

  cd "${module_dir}"
  log "Command: JAVA_HOME=${GRAALVM_HOME} ${cmd[*]}"
  JAVA_HOME="${GRAALVM_HOME}" "${cmd[@]}" 2>&1 | tee "${log_file}"
  local rc=${PIPESTATUS[0]}
  cd "${KOMPILE_ROOT}"

  if [ "${rc}" -ne 0 ]; then
    log "FAILED: native image ${target} (exit ${rc}). See ${log_file}"
    return 1
  fi
  log "DONE: native image ${target}"
}

# All valid native image target names
ALL_NATIVE_TARGETS="cli,component-cli,app,app-lite,staging,ingest,vector,embedding,model-init,vlm-test,training"

# Build all requested native image targets.
# Reads NATIVE_TARGETS (comma-separated, or "all" for everything)
kompile_build_all_native() {
  local -a extra_args=("$@")

  kompile_check_graalvm || return 1
  mkdir -p "${KOMPILE_OUTPUT_DIR}"

  # Expand "all" to the full list
  local effective_targets="${NATIVE_TARGETS}"
  if [ "${effective_targets}" = "all" ]; then
    effective_targets="${ALL_NATIVE_TARGETS}"
  fi

  IFS=',' read -ra targets <<< "${effective_targets}"
  local failed=0
  local total=${#targets[@]}

  log "Building ${total} native image(s): ${effective_targets}"

  for target in "${targets[@]}"; do
    target="$(echo "${target}" | tr -d ' ')"
    if ! kompile_build_native_image "${target}" "${extra_args[@]}"; then
      failed=$((failed + 1))
    fi
  done

  if [ "${failed}" -gt 0 ]; then
    log "FAILED: ${failed}/${total} native image build(s) failed"
    return 1
  fi
  log "All ${total} native image(s) built successfully"
}


# ═══════════════════════════════════════════════════════════════════════════════
# 6. DISTRIBUTION ASSEMBLY
# ═══════════════════════════════════════════════════════════════════════════════

# Assemble through the canonical distribution builder so platform builds,
# direct builds, and AWS releases enforce one payload contract.
#   $1 = platform (optional, defaults to auto-detect)
kompile_assemble_dist() {
  local platform="${1:-$(kompile_detect_platform)}"
  local javacpp_platform backend_type cuda_version backend_profile sdk_classifier distribution_classifier
  javacpp_platform="$(_resolve_javacpp_platform "${platform}")" || return 1
  sdk_classifier="$(_resolve_sdk_classifier "${platform}")" || return 1
  read -r backend_type cuda_version backend_profile < <(_resolve_backend_from_platform "${platform}")
  distribution_classifier="${VARIANT}-${platform}"
  local -a args=(
    "${VARIANT}" --skip-java-build --skip-native
    --platform "${javacpp_platform}"
    --backend-profile "${backend_profile}"
    --sdk-classifier "${sdk_classifier}"
    --distribution-classifier "${distribution_classifier}"
    --output-dir "${KOMPILE_OUTPUT_DIR}"
  )
  if [ -n "${KOMPILE_ACTIVE_SDX_ASSETS_DIR:-}" ]; then
    args+=(--sdx-assets "${KOMPILE_ACTIVE_SDX_ASSETS_DIR}")
  fi
  case "${platform}" in
    *cuda-12.6*) args+=(--cuda-version 12.6) ;;
    *cuda-12.9*) args+=(--cuda-version 12.9) ;;
  esac
  log "Assembling canonical ${distribution_classifier} distribution ZIP"
  (
    cd "${KOMPILE_ROOT}"
    KOMPILE_MAVEN_REPO="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}" \
      "${KOMPILE_ROOT}/build-dist.sh" "${args[@]}"
  )
}


# ═══════════════════════════════════════════════════════════════════════════════
# 7. END-TO-END: DL4J BACKEND + KOMPILE NATIVE IMAGE
# ═══════════════════════════════════════════════════════════════════════════════

# Build everything for a given DL4J platform string:
#   1. Build DL4J backend (installs nd4j-* JARs to local Maven repo)
#   2. Collect SDX runtime SDK bindings from DL4J build output
#   3. Build kompile Java modules (picks up the nd4j-* JARs)
#   4. Build kompile native images
#   5. Assemble and install the complete distribution ZIP
#
# Usage:
#   kompile_build_for_platform linux-x86_64-cuda-12.9
#   NATIVE_TARGETS=cli,app,staging kompile_build_for_platform linux-x86_64
kompile_build_for_platform() {
  local platform="$1"
  local skip_dl4j="${2:-0}"
  local skip_java="${3:-0}"
  local skip_native="${4:-0}"
  local skip_dist="${5:-0}"

  if [ -z "${VARIANT}" ]; then
    case "${platform}" in
      *zluda*) VARIANT="amd-zluda" ;;
      *cuda*) VARIANT="cuda" ;;
      *arm64*) VARIANT="cpu-arm" ;;
      *) VARIANT="cpu-intel" ;;
    esac
  fi

  log "START: kompile build for ${platform}"
  local start_time; start_time=$(date +%s)

  mkdir -p "${KOMPILE_OUTPUT_DIR}"

  if [ "${KOMPILE_PUBLISH}" -eq 1 ] && [ "${skip_dist}" -ne 0 ]; then
    log "ERROR: --publish requires distribution assembly so the ZIP is published with the reactor"
    return 1
  fi

  # Repository mode is explicit and never falls back to a source build.
  if [ -n "${DL4J_MAVEN_REPOSITORY_URL}" ]; then
    skip_dl4j=1
    log "DL4J source: Maven repository ${DL4J_MAVEN_REPOSITORY_URL}"
  fi

  # Ensure source repositories only when they are actually needed.
  kompile_ensure_kompile
  if [ "${skip_dl4j}" -eq 0 ]; then
    kompile_ensure_dl4j
    # Re-source DL4J build-common if it wasn't loaded earlier (first clone)
    if [ "${_DL4J_COMMON_LOADED}" -ne 1 ] && [ -f "${DL4J_PROJECT_ROOT}/build-scripts/build-common.sh" ]; then
      PROJECT_ROOT="${DL4J_PROJECT_ROOT}" source "${DL4J_PROJECT_ROOT}/build-scripts/build-common.sh"
      _DL4J_COMMON_LOADED=1
    fi
  fi

  # Resolve an exact backend profile and classifier from the release matrix.
  local backend_type cuda_version backend_alias javacpp_platform sdk_classifier
  read -r backend_type cuda_version backend_alias < <(_resolve_backend_from_platform "$platform")
  javacpp_platform="$(_resolve_javacpp_platform "$platform")" || return 1
  sdk_classifier="$(_resolve_sdk_classifier "$platform")" || return 1

  local -a extra_mvn_args=(
    "-Dkompile.backend=${backend_alias}"
    "-Djavacpp.platform=${javacpp_platform}"
  )
  if [ "${backend_type}" = "cuda" ]; then
    extra_mvn_args+=("-Dkompile.cuda=true")
  fi

  # Step 1: Build DL4J backend
  if [ "${skip_dl4j}" -eq 0 ]; then
    log "Step 1/5: Building DL4J backend (${platform})"
    kompile_build_dl4j_backend "${platform}" || return 1
  else
    if [ -n "${DL4J_MAVEN_REPOSITORY_URL}" ]; then
      log "Step 1/5: Consuming DL4J ${ND4J_VERSION} from ${DL4J_MAVEN_REPOSITORY_URL}"
    else
      log "Step 1/5: Skipped DL4J backend build (using the configured Maven local repository)"
    fi
  fi

  # Step 2: Resolve the complete DL4J SDK shard.
  KOMPILE_ACTIVE_SDX_ASSETS_DIR=""
  if [ "${skip_dl4j}" -eq 0 ] && [ -n "${DL4J_PROJECT_ROOT:-}" ]; then
    log "Step 2/5: Collecting complete DL4J SDK assets (${platform})"
    kompile_collect_sdx_bindings "${platform}" || return 1
    KOMPILE_ACTIVE_SDX_ASSETS_DIR="${KOMPILE_SDX_OUTPUT_DIR}/${platform}"
  elif [ -n "${DL4J_SDX_ASSETS_DIR}" ]; then
    # Repository lanes always need their exact Maven JAR set. Only runtime
    # lanes additionally require ZIP/AAR payloads, which the validator enforces.
    if [ -z "${DL4J_SDX_ASSETS_DIR}" ]; then
      log "ERROR: repository-only backend builds require --dl4j-sdk-assets DIR"
      return 1
    fi
    if [ -d "${DL4J_SDX_ASSETS_DIR}/${platform}" ]; then
      KOMPILE_ACTIVE_SDX_ASSETS_DIR="${DL4J_SDX_ASSETS_DIR}/${platform}"
    else
      KOMPILE_ACTIVE_SDX_ASSETS_DIR="${DL4J_SDX_ASSETS_DIR}"
    fi
    if [ ! -d "${KOMPILE_ACTIVE_SDX_ASSETS_DIR}" ]; then
      log "ERROR: DL4J SDK assets not found for ${platform}: ${KOMPILE_ACTIVE_SDX_ASSETS_DIR}"
      return 1
    fi
    log "Step 2/5: Using repository companion SDK assets from ${KOMPILE_ACTIVE_SDX_ASSETS_DIR}"
  elif _kompile_lane_requires_runtime "${platform}"; then
    log "ERROR: repository-only ${platform} builds require --dl4j-sdk-assets DIR"
    log "       DL4J publishes runtime ZIP/AAR payloads beside Maven, not inside it."
    return 1
  else
    log "Step 2/5: Collecting Maven-only SDK JARs from the configured repository"
    kompile_collect_sdx_bindings "${platform}" || return 1
    KOMPILE_ACTIVE_SDX_ASSETS_DIR="${KOMPILE_SDX_OUTPUT_DIR}/${platform}"
  fi

  # Step 3: Build kompile Java modules
  if [ "${skip_java}" -eq 0 ]; then
    log "Step 3/5: Building kompile Java modules"
    kompile_build_java_modules "${extra_mvn_args[@]}" || return 1
  else
    log "Step 3/5: Skipped kompile Java build"
  fi

  # Step 4: Build native images
  if [ "${skip_native}" -eq 0 ]; then
    log "Step 4/5: Building native images (${NATIVE_TARGETS})"
    kompile_build_all_native "${extra_mvn_args[@]}" || return 1
  else
    log "Step 4/5: Skipped native image build"
  fi

  # Step 5: Assemble distribution
  if [ "${skip_dist}" -eq 0 ]; then
    log "Step 5/5: Assembling and installing complete distribution ZIP"
    kompile_assemble_dist "${platform}" || return 1
  else
    log "Step 5/5: Skipped distribution assembly"
  fi

  if [ "${KOMPILE_PUBLISH}" -eq 1 ]; then
    local version
    version="$(grep -m1 '<version>' "${KOMPILE_ROOT}/pom.xml" \
      | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')"
    log "Publishing installed reactor and distribution ZIP artifacts"
    MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}" \
      KOMPILE_VERSION="${version}" \
      "${KOMPILE_ROOT}/build-scripts/publish-maven.sh" || return 1
  fi

  local end_time; end_time=$(date +%s)
  local elapsed=$(( end_time - start_time ))
  log "DONE: kompile build for ${platform} (${elapsed}s)"
}
