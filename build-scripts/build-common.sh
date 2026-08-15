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
#   - kompile_assemble_dist()          — Package publishable ZIP/tar Maven assemblies
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

# Clone branch defaults (existing source checkouts are never switched)
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

# Build the checkout that owns these scripts. Never fetch or switch branches in an
# existing checkout: local distributions are commonly built from dirty feature
# branches, and changing that checkout would discard the caller's chosen source.
# Cloning remains available only when an explicit external KOMPILE_ROOT is absent.
kompile_ensure_kompile() {
  if [ -f "${KOMPILE_ROOT}/pom.xml" ] && [ -d "${KOMPILE_ROOT}/build-scripts" ]; then
    log "Using existing Kompile checkout: ${KOMPILE_ROOT}"
    return 0
  fi
  if [ -e "${KOMPILE_ROOT}" ]; then
    log "ERROR: KOMPILE_ROOT exists but is not a Kompile source checkout: ${KOMPILE_ROOT}"
    return 1
  fi
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

if [ -z "${MVN:-}" ]; then
  if command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
  elif command -v mvn.cmd >/dev/null 2>&1; then
    MVN="mvn.cmd"
  elif [ -x "/home/agibsonccc/dev-apps/mvn/bin/mvn" ]; then
    MVN="/home/agibsonccc/dev-apps/mvn/bin/mvn"
  else
    MVN="mvn"
  fi
fi
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
# The release driver passes a native Windows path when this script runs under
# MSYS2/Git Bash. Normalize it before probing the POSIX-visible installation.
if [ -n "${GRAALVM_HOME:-}" ] && command -v cygpath >/dev/null 2>&1; then
  case "${GRAALVM_HOME}" in
    [A-Za-z]:*) GRAALVM_HOME="$(cygpath -u "${GRAALVM_HOME}")" ;;
  esac
fi

kompile_has_native_image() {
  [ -x "$1/bin/native-image" ] || [ -f "$1/bin/native-image.cmd" ]
}

if [ -z "${GRAALVM_HOME:-}" ]; then
  for _candidate in \
    "${HOME}/.sdkman/candidates/java/21.0.10-graal" \
    "${HOME}/.sdkman/candidates/java/17.0.12-graal" \
    "${HOME}/.kompile/graalvm" \
    "${JAVA_HOME:-}"; do
    if [ -n "${_candidate}" ] && kompile_has_native_image "${_candidate}"; then
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
#   model-serving  — request-scoped model serving (kompile-app-subprocess-serving)
#   pipeline-serving — request-scoped unified pipeline execution (kompile-pipeline-serving)
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

# Content-addressed native-image cache. Set KOMPILE_NATIVE_CACHE=0 to disable
# reuse or KOMPILE_NATIVE_FORCE_REBUILD=1 to bypass an otherwise valid hit.
KOMPILE_NATIVE_CACHE="${KOMPILE_NATIVE_CACHE:-1}"
KOMPILE_NATIVE_FORCE_REBUILD="${KOMPILE_NATIVE_FORCE_REBUILD:-0}"
KOMPILE_NATIVE_CACHE_DIR="${KOMPILE_NATIVE_CACHE_DIR:-${HOME}/.cache/kompile/native-images}"
KOMPILE_NATIVE_DEPENDENCY_MANIFEST_CACHE_DIR="${KOMPILE_NATIVE_DEPENDENCY_MANIFEST_CACHE_DIR:-${KOMPILE_NATIVE_CACHE_DIR}/dependency-manifests}"

# Developer builds default to GraalVM quick build (-Ob). Set
# KOMPILE_NATIVE_QUICK_BUILD=0 for optimized/release images. The resolved value
# is passed to Maven and therefore participates in the native cache fingerprint.
KOMPILE_NATIVE_QUICK_BUILD="${KOMPILE_NATIVE_QUICK_BUILD:-1}"
case "${KOMPILE_NATIVE_QUICK_BUILD}" in
  1|true|TRUE|yes|YES) KOMPILE_NATIVE_QUICK_BUILD_PROPERTY=true ;;
  0|false|FALSE|no|NO) KOMPILE_NATIVE_QUICK_BUILD_PROPERTY=false ;;
  *)
    printf 'ERROR: KOMPILE_NATIVE_QUICK_BUILD must be 1/0 or true/false (got %s)\n' \
      "${KOMPILE_NATIVE_QUICK_BUILD}" >&2
    return 1 2>/dev/null || exit 1
    ;;
esac

# SDX bindings output — mirrors DL4J's SDX_OUTPUT_DIR, collected into kompile dist
KOMPILE_SDX_OUTPUT_DIR="${KOMPILE_SDX_OUTPUT_DIR:-${KOMPILE_OUTPUT_DIR}/sdx-sdk}"
# Repository-only builds cannot derive the non-Maven runtime SDK packages from
# DL4J JARs. Point this at an extracted DL4J sdk-assets shard (or a root with
# one subdirectory per platform).
DL4J_SDX_ASSETS_DIR="${DL4J_SDX_ASSETS_DIR:-}"

# Maven flags for Kompile Java builds. Keep these as an array so repository
# URLs and local repository paths are never reparsed by the shell.
KOMPILE_MVN_ARGS=(
  --batch-mode
  --no-transfer-progress
  -DskipTests
  "-Dnative.quickBuild=${KOMPILE_NATIVE_QUICK_BUILD_PROPERTY}"
)

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
  "linux-x86_64-compile-avx2"
  "linux-x86_64-compile-avx512"
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
  "android-arm64-vulkan"
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
  "windows-x86_64-compile"
  "windows-x86_64-vulkan"
  "linux-x86_64-cuda-12.6"
  "linux-x86_64-cuda-12.6-cudnn"
  "linux-x86_64-cuda-12.6-compile"
  "linux-x86_64-cuda-12.9"
  "linux-x86_64-cuda-12.9-cudnn"
  "linux-x86_64-cuda-12.9-compile"
  "windows-x86_64-cuda-12.6"
  "windows-x86_64-cuda-12.6-cudnn"
  "windows-x86_64-cuda-12.6-compile"
  "windows-x86_64-cuda-12.9"
  "windows-x86_64-cuda-12.9-cudnn"
  "windows-x86_64-cuda-12.9-compile"
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
  if [ -z "${GRAALVM_HOME}" ] || ! kompile_has_native_image "${GRAALVM_HOME}"; then
    log "ERROR: GraalVM not found or missing native-image"
    log "Set GRAALVM_HOME or install: sdk install java 21.0.10-graal"
    return 1
  fi
  if [ -x "${GRAALVM_HOME}/bin/native-image" ]; then
    KOMPILE_NATIVE_IMAGE="${GRAALVM_HOME}/bin/native-image"
  else
    KOMPILE_NATIVE_IMAGE="${GRAALVM_HOME}/bin/native-image.cmd"
  fi
  log "GraalVM: ${GRAALVM_HOME}"
  return 0
}

# Run the Windows batch launcher explicitly from MSYS/Git Bash. Git Bash does
# not always mark .cmd files executable, and cmd.exe needs a Windows path.
kompile_native_image_version() {
  case "${KOMPILE_NATIVE_IMAGE}" in
    *.cmd)
      local native_image_path="${KOMPILE_NATIVE_IMAGE}"
      if command -v cygpath >/dev/null 2>&1; then
        native_image_path="$(cygpath -w "${native_image_path}")"
      fi
      MSYS_NO_PATHCONV=1 cmd.exe /d /s /c "\"${native_image_path}\" --version"
      ;;
    *)
      "${KOMPILE_NATIVE_IMAGE}" --version
      ;;
  esac
}

# Resolve the public Kompile backend alias from an attested classifier.
_resolve_backend_from_platform() {
  local platform="$1" backend_type cuda_version backend_profile
  case "$platform" in
    *cuda-12.9-zluda)  backend_type="cuda"; cuda_version="12.9"; backend_profile="zluda" ;;
    *cuda-12.9-cudnn)  backend_type="cuda"; cuda_version="12.9"; backend_profile="cuda-12.9-cudnn" ;;
    *cuda-12.9-compile) backend_type="cuda"; cuda_version="12.9"; backend_profile="cuda-12.9-compile" ;;
    *cuda-12.9)        backend_type="cuda"; cuda_version="12.9"; backend_profile="cuda-12.9" ;;
    *cuda-12.6-cudnn)  backend_type="cuda"; cuda_version="12.6"; backend_profile="cuda-12.6-cudnn" ;;
    *cuda-12.6-compile) backend_type="cuda"; cuda_version="12.6"; backend_profile="cuda-12.6-compile" ;;
    *cuda-12.6)        backend_type="cuda"; cuda_version="12.6"; backend_profile="cuda-12.6" ;;
    *vulkan-compile)   backend_type="vulkan"; cuda_version=""; backend_profile="vulkan-compile" ;;
    *vulkan)           backend_type="vulkan"; cuda_version=""; backend_profile="vulkan" ;;
    *hexagon)          backend_type="hexagon"; cuda_version=""; backend_profile="hexagon" ;;
    *tpu)              backend_type="tpu"; cuda_version=""; backend_profile="tpu" ;;
    *compile-avx512)   backend_type="cpu"; cuda_version=""; backend_profile="cpu-compile-avx512" ;;
    *compile-avx2)     backend_type="cpu"; cuda_version=""; backend_profile="cpu-compile-avx2" ;;
    *onednn-avx512)    backend_type="cpu"; cuda_version=""; backend_profile="cpu-onednn-avx512" ;;
    *onednn-avx2)      backend_type="cpu"; cuda_version=""; backend_profile="cpu-onednn-avx2" ;;
    *onednn)           backend_type="cpu"; cuda_version=""; backend_profile="cpu-onednn" ;;
    *avx512)           backend_type="cpu"; cuda_version=""; backend_profile="cpu-avx512" ;;
    *avx2)             backend_type="cpu"; cuda_version=""; backend_profile="cpu-avx2" ;;
    *armcompute)       backend_type="cpu"; cuda_version=""; backend_profile="cpu-armcompute" ;;
    *mps-compile)      backend_type="cpu"; cuda_version=""; backend_profile="cpu-mps-compile" ;;
    *mps)              backend_type="cpu"; cuda_version=""; backend_profile="cpu-mps" ;;
    *compile-nnapi)    backend_type="cpu"; cuda_version=""; backend_profile="cpu-compile-nnapi" ;;
    *nnapi)            backend_type="cpu"; cuda_version=""; backend_profile="cpu-nnapi" ;;
    *compat)           backend_type="cpu"; cuda_version=""; backend_profile="cpu-compat" ;;
    *compile)          backend_type="cpu"; cuda_version=""; backend_profile="cpu-compile" ;;
    *)                 backend_type="cpu"; cuda_version=""; backend_profile="cpu" ;;
  esac
  printf '%s|%s|%s\n' "${backend_type}" "${cuda_version}" "${backend_profile}"
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
  # Runtime package directories may contain jars/ from an older collection. Replace
  # that directory so a new shard cannot inherit timestamped SNAPSHOT artifacts.
  rm -rf "${dest}/jars"
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
  local namespace artifact_id artifact_dir f
  for namespace in org/eclipse/deeplearning4j org/nd4j; do
    for artifact_id in "${sdk_artifact_ids[@]}"; do
      artifact_dir="${maven_repository}/${namespace}/${artifact_id}/${ND4J_VERSION}"
      [ -d "${artifact_dir}" ] || continue
      # A Maven SNAPSHOT directory can retain timestamped downloads for years. The
      # locally installed canonical filenames are the only release inputs; copying
      # every *.jar makes runtime classpath selection depend on directory ordering.
      for f in \
          "${artifact_dir}/${artifact_id}-${ND4J_VERSION}.jar" \
          "${artifact_dir}/${artifact_id}-${ND4J_VERSION}-${sdk_classifier}.jar"; do
        [ -f "${f}" ] || continue
        cp -p "${f}" "${dest}/jars/"
      done
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
  IFS='|' read -r backend_type lane_cuda_version backend_profile < <(_resolve_backend_from_platform "${platform}")
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


# Compute SHA-256 without depending on a particular output formatter.
kompile_sha256_file() {
  local digest_output
  if command -v sha256sum >/dev/null 2>&1; then
    digest_output="$(sha256sum "$1")" || return 1
  else
    digest_output="$(shasum -a 256 "$1")" || return 1
  fi
  printf '%s' "${digest_output%% *}"
}

kompile_sha256_stdin() {
  local digest_output
  if command -v sha256sum >/dev/null 2>&1; then
    digest_output="$(sha256sum)" || return 1
  else
    digest_output="$(shasum -a 256)" || return 1
  fi
  printf '%s' "${digest_output%% *}"
}

# Hash the source/configuration closure consumed by one standalone native-image
# build. Hashing the whole repository makes an unrelated edit invalidate every
# native worker, even though these builds run from a single module and consume
# the rest of Kompile through resolved Maven artifacts.
kompile_native_source_hash() {
  local module_dir="$1"
  local module_rel ancestor_dir ancestor_rel source_file
  local -a source_paths=()

  case "${module_dir}" in
    "${KOMPILE_ROOT}") module_rel="." ;;
    "${KOMPILE_ROOT}/"*) module_rel="${module_dir#${KOMPILE_ROOT}/}" ;;
    *)
      printf 'Native module is outside the Kompile repository: %s\n' "${module_dir}" >&2
      return 1
      ;;
  esac
  source_paths+=("${module_rel}")

  # Maven parent POMs and root build configuration can change the effective
  # native profile even when the leaf module itself is untouched.
  ancestor_dir="${module_dir}"
  while :; do
    if [ -f "${ancestor_dir}/pom.xml" ]; then
      if [ "${ancestor_dir}" = "${KOMPILE_ROOT}" ]; then
        ancestor_rel="pom.xml"
      else
        ancestor_rel="${ancestor_dir#${KOMPILE_ROOT}/}/pom.xml"
      fi
      source_paths+=("${ancestor_rel}")
    fi
    [ "${ancestor_dir}" = "${KOMPILE_ROOT}" ] && break
    ancestor_dir="${ancestor_dir%/*}"
  done
  source_paths+=(".mvn" "build-scripts/build-common.sh" "build-scripts/NativeImageDependencyFingerprint.java")

  {
    printf 'source-schema=kompile-native-source-v2\n'
    # Android source receipts deliberately use the same path+content identity
    # before and after a new file is committed. Preserve that property here so
    # promoting a validated helper from untracked to tracked cannot fork AOT.
    git -C "${KOMPILE_ROOT}" ls-files -z --cached --others --exclude-standard -- \
      "${source_paths[@]}" \
      | sort -zu \
      | while IFS= read -r -d '' source_file; do
          [ -f "${KOMPILE_ROOT}/${source_file}" ] || continue
          printf 'path=%s\nsha256=%s\n' "${source_file}" \
            "$(kompile_sha256_file "${KOMPILE_ROOT}/${source_file}")"
        done
  } | kompile_sha256_stdin
}

# Split the resolved runtime classpath into the same independent stages used
# by the Android builders: semantic Java/resources consumed by Native Image and
# binary payloads side-loaded by the distribution. The helper hashes uncompressed
# archive members, so ZIP timestamps/compression and native-only rebuilds do not
# invalidate Graal analysis.
kompile_native_dependency_fingerprints() {
  local classpath_file="$1"
  local helper="${KOMPILE_ROOT}/build-scripts/NativeImageDependencyFingerprint.java"
  local output schema="" aot="" runtime="" aot_dependencies="" runtime_dependencies="" native_members=""
  local key value

  [ -f "${helper}" ] || {
    printf 'Native dependency fingerprint helper is missing: %s\n' "${helper}" >&2
    return 1
  }
  output="$("${GRAALVM_HOME}/bin/java" --source 17 "${helper}" "${classpath_file}" \
    "${KOMPILE_NATIVE_DEPENDENCY_MANIFEST_CACHE_DIR}")" || return 1

  while IFS='=' read -r key value; do
    case "${key}" in
      schema)
        [ -z "${schema}" ] || return 1
        schema="${value}"
        ;;
      aot)
        [ -z "${aot}" ] || return 1
        aot="${value}"
        ;;
      runtime)
        [ -z "${runtime}" ] || return 1
        runtime="${value}"
        ;;
      aot_dependencies)
        [ -z "${aot_dependencies}" ] || return 1
        aot_dependencies="${value}"
        ;;
      runtime_dependencies)
        [ -z "${runtime_dependencies}" ] || return 1
        runtime_dependencies="${value}"
        ;;
      native_members)
        [ -z "${native_members}" ] || return 1
        native_members="${value}"
        ;;
      *)
        printf 'Unknown native dependency fingerprint field: %s\n' "${key}" >&2
        return 1
        ;;
    esac
  done <<< "${output}"

  [ "${schema}" = "kompile-native-dependency-fingerprints-v1" ] || return 1
  [[ "${aot}" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "${runtime}" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "${aot_dependencies}" =~ ^[0-9]+$ ]] || return 1
  [[ "${runtime_dependencies}" =~ ^[0-9]+$ ]] || return 1
  [[ "${native_members}" =~ ^[0-9]+$ ]] || return 1
  printf '%s %s\n' "${aot}" "${runtime}"
}

# Fingerprint every input that can change a native image while avoiding a full
# Java/package build. Module sources and effective parent configuration are
# hashed directly. Resolved dependencies use independent semantic-AOT and
# side-loaded-runtime identities, matching the Android stage/receipt model.
kompile_native_fingerprint() {
  local target="$1"
  local module_dir="$2"
  local profile="$3"
  local image_name="$4"
  shift 4
  local -a fingerprint_args=("$@")

  mkdir -p "${KOMPILE_OUTPUT_DIR}"
  local probe_dir classpath_file probe_log
  probe_dir="$(mktemp -d "${KOMPILE_OUTPUT_DIR}/.native-cache-probe.XXXXXX")" || return 1
  classpath_file="${probe_dir}/dependencies.classpath"
  probe_log="${probe_dir}/maven.log"

  local -a dependency_cmd=(
    "${MVN}" -q "-P${profile}" -DskipTests
    "${KOMPILE_MVN_ARGS[@]}"
    "${KOMPILE_DEPENDENCY_MAVEN_ARGS[@]}"
    "${fingerprint_args[@]}"
    dependency:build-classpath
    "-Dmdep.outputFile=${classpath_file}"
    -Dmdep.includeScope=runtime
  )
  if ! (cd "${module_dir}" && JAVA_HOME="${GRAALVM_HOME}" "${dependency_cmd[@]}")       >"${probe_log}" 2>&1; then
    printf 'Native cache fingerprint dependency resolution failed; see %s\n'       "${probe_log}" >&2
    rm -rf "${probe_dir}"
    return 1
  fi

  local source_hash dependency_fingerprints aot_dependency_hash runtime_dependency_hash graal_version
  if git -C "${KOMPILE_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    source_hash="$(kompile_native_source_hash "${module_dir}")" || {
      rm -rf "${probe_dir}"
      return 1
    }
  else
    source_hash="$(
      find "${KOMPILE_ROOT}" -type f         ! -path '*/target/*' ! -path '*/dist/*' ! -path '*/.git/*' -print0         | sort -z         | while IFS= read -r -d '' source_file; do
            printf 'path=%s\nsha256=%s\n' "${source_file#${KOMPILE_ROOT}/}"               "$(kompile_sha256_file "${source_file}")"
          done         | kompile_sha256_stdin
    )"
  fi

  dependency_fingerprints="$(kompile_native_dependency_fingerprints "${classpath_file}")" || {
    rm -rf "${probe_dir}"
    return 1
  }
  read -r aot_dependency_hash runtime_dependency_hash <<< "${dependency_fingerprints}"
  if [[ ! "${aot_dependency_hash}" =~ ^[0-9a-f]{64}$ ]] \
      || [[ ! "${runtime_dependency_hash}" =~ ^[0-9a-f]{64}$ ]]; then
    rm -rf "${probe_dir}"
    return 1
  fi
  graal_version="$(kompile_native_image_version 2>&1)"

  local aot_fingerprint runtime_fingerprint
  aot_fingerprint="$({
    printf 'schema=kompile-native-aot-cache-v3\n'
    printf 'target=%s\nmodule=%s\nprofile=%s\nimage=%s\n' \
      "${target}" "${module_dir#${KOMPILE_ROOT}/}" "${profile}" "${image_name}"
    printf 'nd4j=%s\nvariant=%s\ngraal-home=%s\ngraal=%s\n' \
      "${ND4J_VERSION}" "${VARIANT}" "${GRAALVM_HOME}" "${graal_version}"
    printf 'sources=%s\naot-dependencies=%s\n' "${source_hash}" "${aot_dependency_hash}"
    printf 'maven-arg=%q\n' "${KOMPILE_MVN_ARGS[@]}"
    printf 'dependency-arg=%q\n' "${KOMPILE_DEPENDENCY_MAVEN_ARGS[@]}"
    printf 'arg=%q\n' "${fingerprint_args[@]}"
  } | kompile_sha256_stdin)" || {
    rm -rf "${probe_dir}"
    return 1
  }

  runtime_fingerprint="$({
    printf 'schema=kompile-native-runtime-cache-v1\n'
    printf 'target=%s\nmodule=%s\nprofile=%s\nimage=%s\n' \
      "${target}" "${module_dir#${KOMPILE_ROOT}/}" "${profile}" "${image_name}"
    printf 'nd4j=%s\nvariant=%s\nruntime-payloads=%s\n' \
      "${ND4J_VERSION}" "${VARIANT}" "${runtime_dependency_hash}"
    printf 'dependency-arg=%q\n' "${KOMPILE_DEPENDENCY_MAVEN_ARGS[@]}"
    printf 'arg=%q\n' "${fingerprint_args[@]}"
  } | kompile_sha256_stdin)" || {
    rm -rf "${probe_dir}"
    return 1
  }

  rm -rf "${probe_dir}"
  printf '%s %s\n' "${aot_fingerprint}" "${runtime_fingerprint}"
}

KOMPILE_NATIVE_CACHE_RECEIPT_SCHEMA=kompile-native-cache-receipt-v3
KOMPILE_NATIVE_RECEIPT_RUNTIME=""
KOMPILE_NATIVE_RECEIPT_CHECKSUM=""

kompile_native_write_cache_receipt() {
  local receipt="$1"
  local aot_fingerprint="$2"
  local runtime_fingerprint="$3"
  local checksum="$4"
  local temporary_receipt="${receipt}.tmp.$$"

  [[ "${aot_fingerprint}" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "${runtime_fingerprint}" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "${checksum}" =~ ^[0-9a-f]{64}$ ]] || return 1
  {
    printf 'schema=%s\n' "${KOMPILE_NATIVE_CACHE_RECEIPT_SCHEMA}"
    printf 'aot_fingerprint=%s\n' "${aot_fingerprint}"
    printf 'runtime_fingerprint=%s\n' "${runtime_fingerprint}"
    printf 'artifact_sha256=%s\n' "${checksum}"
  } > "${temporary_receipt}" || {
    rm -f "${temporary_receipt}"
    return 1
  }
  mv -f "${temporary_receipt}" "${receipt}"
}

kompile_native_validate_cache_receipt() {
  local receipt="$1"
  local expected_aot="$2"
  local schema="" aot_fingerprint="" runtime_fingerprint="" checksum=""
  local key value

  [ -f "${receipt}" ] && [ ! -L "${receipt}" ] && [ -s "${receipt}" ] || return 1
  while IFS='=' read -r key value; do
    case "${key}" in
      schema)
        [ -z "${schema}" ] || return 1
        schema="${value}"
        ;;
      aot_fingerprint)
        [ -z "${aot_fingerprint}" ] || return 1
        aot_fingerprint="${value}"
        ;;
      runtime_fingerprint)
        [ -z "${runtime_fingerprint}" ] || return 1
        runtime_fingerprint="${value}"
        ;;
      artifact_sha256)
        [ -z "${checksum}" ] || return 1
        checksum="${value}"
        ;;
      *)
        return 1
        ;;
    esac
  done < "${receipt}"

  [ "${schema}" = "${KOMPILE_NATIVE_CACHE_RECEIPT_SCHEMA}" ] || return 1
  [ "${aot_fingerprint}" = "${expected_aot}" ] || return 1
  [[ "${runtime_fingerprint}" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "${checksum}" =~ ^[0-9a-f]{64}$ ]] || return 1
  KOMPILE_NATIVE_RECEIPT_RUNTIME="${runtime_fingerprint}"
  KOMPILE_NATIVE_RECEIPT_CHECKSUM="${checksum}"
}

kompile_restore_cached_native_image() {
  local target="$1"
  local image_path="$2"
  local aot_fingerprint="$3"
  local runtime_fingerprint="$4"
  local target_metadata="${image_path}.native-cache"
  local actual_checksum

  if [ -x "${image_path}" ] && [ ! -L "${image_path}" ] \
      && kompile_native_validate_cache_receipt "${target_metadata}" "${aot_fingerprint}"; then
    actual_checksum="$(kompile_sha256_file "${image_path}")"
    if [ "${actual_checksum}" = "${KOMPILE_NATIVE_RECEIPT_CHECKSUM}" ]; then
      kompile_native_write_cache_receipt "${target_metadata}" \
        "${aot_fingerprint}" "${runtime_fingerprint}" "${actual_checksum}" || return 1
      log "CACHE HIT: reusing native image ${target} from ${image_path} (AOT ${aot_fingerprint}, runtime ${runtime_fingerprint})"
      return 0
    fi
  fi

  local cache_dir="${KOMPILE_NATIVE_CACHE_DIR}/${target}/${aot_fingerprint}"
  local cached_image="${cache_dir}/$(basename "${image_path}")"
  local cached_metadata="${cached_image}.native-cache"
  local temporary_image
  if [ ! -x "${cached_image}" ] || [ -L "${cached_image}" ] \
      || ! kompile_native_validate_cache_receipt "${cached_metadata}" "${aot_fingerprint}"; then
    return 1
  fi
  actual_checksum="$(kompile_sha256_file "${cached_image}")"
  [ "${actual_checksum}" = "${KOMPILE_NATIVE_RECEIPT_CHECKSUM}" ] || return 1

  mkdir -p "$(dirname "${image_path}")"
  temporary_image="$(mktemp "$(dirname "${image_path}")/.$(basename "${image_path}").native-cache.XXXXXXXX")" || return 1
  if ! cp -p "${cached_image}" "${temporary_image}" \
      || [ "$(kompile_sha256_file "${temporary_image}")" != "${actual_checksum}" ]; then
    rm -f "${temporary_image}"
    return 1
  fi
  chmod +x "${temporary_image}"
  mv -f "${temporary_image}" "${image_path}"
  kompile_native_write_cache_receipt "${target_metadata}" \
    "${aot_fingerprint}" "${runtime_fingerprint}" "${actual_checksum}" || return 1
  log "CACHE HIT: restored native image ${target} from ${cache_dir} (runtime ${runtime_fingerprint})"
}

kompile_publish_cached_native_image() {
  local target="$1"
  local image_path="$2"
  local aot_fingerprint="$3"
  local runtime_fingerprint="$4"
  local checksum cache_dir cached_image cached_metadata temporary_image

  [ -x "${image_path}" ] && [ ! -L "${image_path}" ] || return 1
  checksum="$(kompile_sha256_file "${image_path}")" || return 1
  kompile_native_write_cache_receipt "${image_path}.native-cache" \
    "${aot_fingerprint}" "${runtime_fingerprint}" "${checksum}" || return 1

  cache_dir="${KOMPILE_NATIVE_CACHE_DIR}/${target}/${aot_fingerprint}"
  cached_image="${cache_dir}/$(basename "${image_path}")"
  cached_metadata="${cached_image}.native-cache"
  mkdir -p "${cache_dir}"
  [ -d "${cache_dir}" ] && [ ! -L "${cache_dir}" ] || return 1

  if [ -x "${cached_image}" ] && [ ! -L "${cached_image}" ] \
      && kompile_native_validate_cache_receipt "${cached_metadata}" "${aot_fingerprint}" \
      && [ "$(kompile_sha256_file "${cached_image}")" = "${checksum}" ]; then
    kompile_native_write_cache_receipt "${cached_metadata}" \
      "${aot_fingerprint}" "${runtime_fingerprint}" "${checksum}" || return 1
    log "Native AOT cache already contains ${target}: ${cache_dir}"
    return 0
  fi

  temporary_image="${cached_image}.tmp.$$"
  if ! cp -p "${image_path}" "${temporary_image}" \
      || [ "$(kompile_sha256_file "${temporary_image}")" != "${checksum}" ]; then
    rm -f "${temporary_image}"
    return 1
  fi
  chmod +x,a-w "${temporary_image}"
  mv -f "${temporary_image}" "${cached_image}"
  kompile_native_write_cache_receipt "${cached_metadata}" \
    "${aot_fingerprint}" "${runtime_fingerprint}" "${checksum}" || return 1
  log "Cached native image ${target}: ${cache_dir} (runtime ${runtime_fingerprint})"
}

# Build a single native image target.
#   $1 = target name (see NATIVE_TARGETS comment above for valid values)
#   remaining arguments = extra Maven arguments (optional)
kompile_build_native_image() {
  local target="$1"
  shift
  local -a extra_args=("$@")

  kompile_check_graalvm || return 1

  local module_dir profile image_name image_path log_file
  case "${target}" in
    # ── Standalone CLIs ────────────────────────────────────────────────
    cli)
      module_dir="${KOMPILE_ROOT}/kompile-cli"
      profile="native"
      image_name="kompile-cli-main"
      # Select the actual CLI module instead of activating every sibling
      # native profile in the kompile-cli aggregator.
      extra_args+=("-pl" "kompile-cli-main" "-am")
      ;;
    component-cli)
      module_dir="${KOMPILE_ROOT}/kompile-cli/kompile-component-cli"
      profile="native"
      image_name="kompile-component"
      ;;
    # ── Application servers ────────────────────────────────────────────
    app)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main"
      profile="native"
      image_name="kompile-app"
      extra_args+=("-Dkompile.dist=true" "-Dkompile.uber")
      ;;
    chat)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-chat"
      profile="native"
      image_name="kompile-chat"
      extra_args+=("-Dkompile.dist=true")
      ;;
    crawl-manager)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-crawl-manager"
      profile="native"
      image_name="kompile-crawl-manager"
      extra_args+=("-Dkompile.dist=true")
      ;;
    sample)
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
      # The request-scoped staging worker has no public UI. Avoid rebuilding the
      # Angular application before every native metadata iteration.
      extra_args+=("-Dskip.ui")
      ;;
    # ── Standalone request-scoped subprocess runtimes ─────────────────
    model-serving)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving"
      profile="native"
      image_name="kompile-model-serving"
      # The native image consumes target/classes plus its resolved dependency
      # classpath; rebuilding the 2+ GiB executable JAR adds no native inputs.
      extra_args+=("-Dshade.skip=true")
      ;;
    pipeline-serving)
      module_dir="${KOMPILE_ROOT}/kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving"
      profile="native"
      image_name="kompile-pipeline-serving"
      ;;
    # ── Legacy subprocess native images (built from kompile-app-main) ────
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
      log "Valid targets: cli, component-cli, app, chat, crawl-manager, sample, app-lite, staging, model-serving, pipeline-serving, ingest, vector, embedding, model-init, vlm-test, training"
      return 1
      ;;
  esac
  case "${target}" in
    ingest|vector|embedding|model-init|vlm-test|training)
      extra_args+=("-Dkompile.native.side-load=true")
      ;;
  esac
  case "${target}" in
    cli) image_path="${module_dir}/kompile-cli-main/target/${image_name}" ;;
    *) image_path="${module_dir}/target/${image_name}" ;;
  esac
  mkdir -p "${KOMPILE_OUTPUT_DIR}"
  log_file="${KOMPILE_OUTPUT_DIR}/native-${target}.log"

  log "Building native image: ${target} (${image_name})"
  log "  Module: ${module_dir}"
  log "  Profile: ${profile}"
  log "  GraalVM: ${GRAALVM_HOME}"

  kompile_prepare_dependency_maven_args

  local native_fingerprints="" native_aot_fingerprint="" native_runtime_fingerprint=""
  if [ "${KOMPILE_NATIVE_CACHE}" = "1" ]; then
    if native_fingerprints="$(kompile_native_fingerprint \
        "${target}" "${module_dir}" "${profile}" "${image_name}" \
        "${extra_args[@]}")"; then
      read -r native_aot_fingerprint native_runtime_fingerprint <<< "${native_fingerprints}"
      if [[ ! "${native_aot_fingerprint}" =~ ^[0-9a-f]{64}$ ]] \
          || [[ ! "${native_runtime_fingerprint}" =~ ^[0-9a-f]{64}$ ]]; then
        log "WARNING: native cache returned malformed independent-stage fingerprints; building ${target} normally"
        native_aot_fingerprint=""
        native_runtime_fingerprint=""
      elif [ "${KOMPILE_NATIVE_FORCE_REBUILD}" != "1" ] \
          && kompile_restore_cached_native_image \
            "${target}" "${image_path}" "${native_aot_fingerprint}" \
            "${native_runtime_fingerprint}"; then
        return 0
      else
        log "CACHE MISS: native image ${target} (AOT ${native_aot_fingerprint}, runtime ${native_runtime_fingerprint})"
      fi
    else
      log "WARNING: native cache fingerprint unavailable; building ${target} normally"
      native_aot_fingerprint=""
      native_runtime_fingerprint=""
    fi
  fi

  local -a cmd=(
    "${MVN}" package "-P${profile}" -DskipTests
    "${KOMPILE_MVN_ARGS[@]}"
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
  if [ ! -x "${image_path}" ]; then
    log "FAILED: native image ${target} reported success but ${image_path} is missing"
    return 1
  fi
  if [ -n "${native_aot_fingerprint}" ] && [ -n "${native_runtime_fingerprint}" ]; then
    kompile_publish_cached_native_image \
      "${target}" "${image_path}" "${native_aot_fingerprint}" \
      "${native_runtime_fingerprint}" \
      || log "WARNING: could not publish native cache entry for ${target}"
  fi
  log "DONE: native image ${target}"
}

# All valid native image target names
ALL_NATIVE_TARGETS="cli,component-cli,app,chat,crawl-manager,sample,app-lite,staging,model-serving,pipeline-serving,ingest,vector,embedding,model-init,vlm-test,training"

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
  IFS='|' read -r backend_type cuda_version backend_profile < <(_resolve_backend_from_platform "${platform}")
  distribution_classifier="${VARIANT}-${platform}"
  local -a args=(
    "${VARIANT}" --skip-java-build --skip-native
    --platform "${javacpp_platform}"
    --backend-profile "${backend_profile}"
    --sdk-classifier "${sdk_classifier}"
    --distribution-classifier "${distribution_classifier}"
    --output-dir "${KOMPILE_OUTPUT_DIR}"
  )
  if [ -n "${KOMPILE_VERSION:-}" ]; then
    args+=(--version "${KOMPILE_VERSION}")
  fi
  if [ -n "${KOMPILE_ACTIVE_SDX_ASSETS_DIR:-}" ]; then
    args+=(--sdx-assets "${KOMPILE_ACTIVE_SDX_ASSETS_DIR}")
  fi
  case "${platform}" in
    *cuda-12.6*) args+=(--cuda-version 12.6) ;;
    *cuda-12.9*) args+=(--cuda-version 12.9) ;;
  esac
  log "Assembling canonical ${distribution_classifier} distribution archives"
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
#   5. Assemble and install the complete ZIP/tar Maven distribution artifacts
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
    log "ERROR: --publish requires distribution assembly so ZIP/tar artifacts are published with the reactor"
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
  IFS='|' read -r backend_type cuda_version backend_alias < <(_resolve_backend_from_platform "$platform")
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

  # Step 2: Resolve the complete DL4J SDK shard only when assembling a
  # distribution. Native-image-only builds consume their exact Maven classpath
  # and must not be blocked on ZIP/AAR payloads they never package.
  KOMPILE_ACTIVE_SDX_ASSETS_DIR=""
  if [ "${skip_dist}" -ne 0 ]; then
    log "Step 2/5: Skipped DL4J SDK asset collection (distribution assembly disabled)"
  elif [ "${skip_dl4j}" -eq 0 ] && [ -n "${DL4J_PROJECT_ROOT:-}" ]; then
    log "Step 2/5: Collecting complete DL4J SDK assets (${platform})"
    kompile_collect_sdx_bindings "${platform}" || return 1
    KOMPILE_ACTIVE_SDX_ASSETS_DIR="${KOMPILE_SDX_OUTPUT_DIR}/${platform}"
  elif [ -n "${DL4J_SDX_ASSETS_DIR}" ]; then
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
    log "ERROR: repository-only ${platform} distribution builds require --dl4j-sdk-assets DIR"
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
    log "Step 5/5: Assembling complete ZIP/tar Maven distribution artifacts"
    kompile_assemble_dist "${platform}" || return 1
  else
    log "Step 5/5: Skipped distribution assembly"
  fi

  if [ "${KOMPILE_PUBLISH}" -eq 1 ]; then
    local version
    version="$(grep -m1 '<version>' "${KOMPILE_ROOT}/pom.xml" \
      | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')"
    log "Publishing installed reactor and distribution ZIP/tar artifacts"
    MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}" \
      KOMPILE_VERSION="${version}" \
      "${KOMPILE_ROOT}/build-scripts/publish-maven.sh" || return 1
  fi

  local end_time; end_time=$(date +%s)
  local elapsed=$(( end_time - start_time ))
  log "DONE: kompile build for ${platform} (${elapsed}s)"
}
