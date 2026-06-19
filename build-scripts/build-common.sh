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

# Distribution variant
VARIANT="${VARIANT:-cli-only}"

# Output directory
KOMPILE_OUTPUT_DIR="${KOMPILE_OUTPUT_DIR:-${KOMPILE_ROOT}/dist}"

# SDX bindings output — mirrors DL4J's SDX_OUTPUT_DIR, collected into kompile dist
KOMPILE_SDX_OUTPUT_DIR="${KOMPILE_SDX_OUTPUT_DIR:-${KOMPILE_OUTPUT_DIR}/sdx-sdk}"

# Maven flags for kompile Java builds
KOMPILE_MVN_FLAGS="--batch-mode -Dmaven.test.skip=true"

# All supported kompile platform targets (subset of DL4J platforms)
KOMPILE_PLATFORMS=(
  # CPU
  "linux-x86_64"
  "linux-x86_64-onednn"
  "linux-arm64"
  "macosx-arm64"
  "windows-x86_64"
  "windows-x86_64-onednn"
  # CUDA
  "linux-x86_64-cuda-12.6"
  "linux-x86_64-cuda-12.9"
  "linux-x86_64-cuda-13.1"
  "windows-x86_64-cuda-12.6"
  "windows-x86_64-cuda-12.9"
  # ROCm/ZLUDA
  "linux-x86_64-rocm-6.4"
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

# Resolve backend type (cpu/cuda) and artifact from platform string
_resolve_backend_from_platform() {
  local platform="$1"
  case "$platform" in
    *cuda-13.1*) echo "cuda" "13.1" "nd4j-cuda-13.1" ;;
    *cuda-12.9*) echo "cuda" "12.9" "nd4j-cuda-12.9" ;;
    *cuda-12.6*) echo "cuda" "12.6" "nd4j-cuda-12.6" ;;
    *rocm*)      echo "cpu"  ""     "nd4j-native" ;;     # ZLUDA uses CPU-side nd4j-native
    *)           echo "cpu"  ""     "nd4j-native" ;;
  esac
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

# Collect SDX runtime SDK artifacts produced by the DL4J build into the
# kompile output directory. DL4J's build_platform() calls package_sdx_bindings()
# which writes to DL4J's SDX_OUTPUT_DIR. This function copies those artifacts
# into kompile's own SDX output location.
#
# Usage: kompile_collect_sdx_bindings <platform>
kompile_collect_sdx_bindings() {
  local platform="$1"

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

  # Determine source directory
  local src_dir=""
  if [ -d "${dl4j_sdx_dir}" ] && [ -n "$(ls -A "${dl4j_sdx_dir}" 2>/dev/null)" ]; then
    src_dir="${dl4j_sdx_dir}"
  elif [ -d "${blasbuild_dist}" ] && [ -n "$(ls -A "${blasbuild_dist}" 2>/dev/null)" ]; then
    src_dir="${blasbuild_dist}"
  else
    log "SDX bindings not found for ${platform} — skipping collection"
    return 0
  fi

  local dest="${KOMPILE_SDX_OUTPUT_DIR}/${platform}"
  mkdir -p "${dest}"

  local count=0

  # Copy ZIP bundles
  while IFS= read -r -d '' f; do
    cp -p "${f}" "${dest}/"
    count=$((count + 1))
  done < <(find "${src_dir}" -maxdepth 2 -type f -name "*.zip" -print0 2>/dev/null)

  # Android: .aar files
  if [[ "${platform}" == android-* ]]; then
    while IFS= read -r -d '' f; do
      cp -p "${f}" "${dest}/"
      count=$((count + 1))
    done < <(find "${src_dir}" -maxdepth 2 -type f -name "*.aar" -print0 2>/dev/null)
  fi

  # macOS: .xcframework bundles
  if [[ "${platform}" == macosx-* ]]; then
    while IFS= read -r -d '' d; do
      cp -rp "${d}" "${dest}/"
      count=$((count + 1))
    done < <(find "${src_dir}" -maxdepth 2 -type d -name "*.xcframework" -print0 2>/dev/null)
  fi

  # Also copy binding.json if present in the bindings tree
  while IFS= read -r -d '' f; do
    local rel_dir
    rel_dir="$(dirname "${f}")"
    rel_dir="${rel_dir##*/}"  # variant name (cpu, cuda, etc.)
    mkdir -p "${dest}/${rel_dir}"
    cp -p "${f}" "${dest}/${rel_dir}/"
    count=$((count + 1))
  done < <(find "${blasbuild_dir}/sdx-runtime-sdk/bindings" -name "binding.json" -print0 2>/dev/null)

  if [ "${count}" -gt 0 ]; then
    log "SDX bindings collected: ${count} artifact(s) → ${dest}"
  else
    log "WARNING: SDX dist directory found but contained no artifacts"
  fi
}


# ═══════════════════════════════════════════════════════════════════════════════
# 4. KOMPILE JAVA BUILD
# ═══════════════════════════════════════════════════════════════════════════════

# Install all kompile Java modules to local Maven repo.
# This is a prerequisite for native image builds.
kompile_build_java_modules() {
  local extra_flags="${1:-}"
  log "Building kompile Java modules"
  cd "${KOMPILE_ROOT}"

  local cmd="${MVN} clean install ${KOMPILE_MVN_FLAGS} ${extra_flags}"
  log "Command: ${cmd}"
  eval "${cmd}" 2>&1 | tee "${KOMPILE_OUTPUT_DIR}/kompile-java-build.log"
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
#   $2 = extra Maven flags (optional)
kompile_build_native_image() {
  local target="$1"
  local extra_flags="${2:-}"

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

  local cmd="JAVA_HOME=${GRAALVM_HOME} ${MVN} package \
    -P${profile} -DskipTests \
    ${extra_flags}"

  cd "${module_dir}"
  log "Command: ${cmd}"
  eval "${cmd}" 2>&1 | tee "${log_file}"
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
  local extra_flags="${1:-}"

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
    if ! kompile_build_native_image "${target}" "${extra_flags}"; then
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

# Assemble a distribution tarball from built artifacts.
# Mirrors build-dist.sh logic but driven by variables, not CLI args.
#   $1 = platform (optional, defaults to auto-detect)
kompile_assemble_dist() {
  local platform="${1:-$(kompile_detect_platform)}"

  local version
  version="$(grep -m1 '<version>' "${KOMPILE_ROOT}/pom.xml" \
    | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')"

  local dist_name="kompile-dist-${version}-${VARIANT}-${platform}"
  local dist_dir="${KOMPILE_OUTPUT_DIR}/${dist_name}"

  log "Assembling distribution: ${dist_name}"
  rm -rf "${dist_dir}"
  mkdir -p "${dist_dir}"/{bin,lib,config,data}

  # ── Collect all native image binaries ──────────────────────────────────────
  # Each entry: source_path → dist_name
  # Uses || true so missing binaries (not built) are silently skipped.
  local -a NATIVE_BINARIES=(
    # CLIs
    "${KOMPILE_ROOT}/kompile-cli/target/kompile-cli-main:kompile-cli"
    "${KOMPILE_ROOT}/kompile-cli/kompile-component-cli/target/kompile-component:kompile-component"
    # Application servers
    "${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app:kompile-app"
    "${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-lite/target/kompile-app-lite-native:kompile-app-lite"
    # Model staging orchestrator
    "${KOMPILE_ROOT}/kompile-app/kompile-models/kompile-model-staging/target/kompile-model-staging:kompile-model-staging"
    # Subprocess binaries (built from kompile-app-main with per-subprocess profiles)
    "${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main/target/kompile-ingest:kompile-ingest"
    "${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main/target/kompile-vector:kompile-vector"
    "${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main/target/kompile-embedding:kompile-embedding"
    "${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main/target/kompile-model-init:kompile-model-init"
    "${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main/target/kompile-vlm-test:kompile-vlm-test"
    "${KOMPILE_ROOT}/kompile-app/kompile-app-parent/kompile-app-main/target/kompile-training:kompile-training"
  )

  local bin_count=0
  for entry in "${NATIVE_BINARIES[@]}"; do
    local src="${entry%%:*}"
    local dest_name="${entry##*:}"
    if [ -f "${src}" ]; then
      cp "${src}" "${dist_dir}/bin/${dest_name}"
      chmod +x "${dist_dir}/bin/${dest_name}"
      log "  bin/${dest_name} ($(du -h "${src}" | cut -f1))"
      bin_count=$((bin_count + 1))
    fi
  done
  log "  ${bin_count} native image(s) collected"

  # Copy build scripts into the distribution for platform rebuilds
  local scripts_src="${KOMPILE_ROOT}/build-scripts"
  if [ -d "${scripts_src}" ]; then
    mkdir -p "${dist_dir}/build-scripts"
    cp "${scripts_src}/"*.sh "${dist_dir}/build-scripts/"
    chmod +x "${dist_dir}/build-scripts/"*.sh
    local script_count
    script_count=$(ls "${dist_dir}/build-scripts/"*.sh 2>/dev/null | wc -l)
    log "  build-scripts/ (${script_count} scripts)"
  fi

  # Copy native .so libraries if extracted
  if [ -d "${KOMPILE_ROOT}/kompile-rag-builds/kompile-sample/project/target/native-libs" ]; then
    local so_count
    so_count=$(find "${KOMPILE_ROOT}/kompile-rag-builds/kompile-sample/project/target/native-libs" \
      -maxdepth 1 \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dll' \) 2>/dev/null | wc -l)
    if [ "${so_count}" -gt 0 ]; then
      cp -a "${KOMPILE_ROOT}/kompile-rag-builds/kompile-sample/project/target/native-libs/"*.so* \
        "${dist_dir}/lib/" 2>/dev/null || true
      chmod +x "${dist_dir}/lib/"*.so* 2>/dev/null || true
      log "  lib/ (${so_count} native libraries)"
    fi
  fi

  # Copy SDX runtime SDK artifacts if present
  local sdx_platform_dir="${KOMPILE_SDX_OUTPUT_DIR}/${platform}"
  if [ -d "${sdx_platform_dir}" ] && [ -n "$(ls -A "${sdx_platform_dir}" 2>/dev/null)" ]; then
    mkdir -p "${dist_dir}/sdx-sdk"
    cp -rp "${sdx_platform_dir}"/* "${dist_dir}/sdx-sdk/"
    local sdx_count
    sdx_count=$(find "${dist_dir}/sdx-sdk" -maxdepth 1 \( -name '*.zip' -o -name '*.aar' -o -name '*.xcframework' \) 2>/dev/null | wc -l)
    log "  sdx-sdk/ (${sdx_count} artifact(s))"
  fi

  # Create seed data directories
  mkdir -p "${dist_dir}/data"/{input_documents/uploads,shared_files}
  mkdir -p "${dist_dir}/data"/{prompt-templates,models/.staging}
  mkdir -p "${dist_dir}/data"/{logs,tool-definitions,folders}
  mkdir -p "${dist_dir}/data"/{mcp-servers,mcp-bridges,pids}

  # Metadata
  local sdx_present="false"
  [ -d "${dist_dir}/sdx-sdk" ] && sdx_present="true"

  echo "${version}" > "${dist_dir}/.version"
  echo "${VARIANT}" > "${dist_dir}/.variant"
  cat > "${dist_dir}/.dist-info.json" << EOF
{
  "version": "${version}",
  "variant": "${VARIANT}",
  "platform": "${platform}",
  "sdxSdk": ${sdx_present},
  "buildDate": "$(date -Iseconds)"
}
EOF

  # Create tarball
  local archive="${KOMPILE_OUTPUT_DIR}/${dist_name}.tar.gz"
  tar -czf "${archive}" -C "${KOMPILE_OUTPUT_DIR}" "${dist_name}/"
  sha256sum "${archive}" > "${archive}.sha256" 2>/dev/null || shasum -a 256 "${archive}" > "${archive}.sha256"

  log "Distribution: ${archive}"
  log "Size: $(du -h "${archive}" | cut -f1)"
  log "SHA256: $(cat "${archive}.sha256")"
}


# ═══════════════════════════════════════════════════════════════════════════════
# 7. END-TO-END: DL4J BACKEND + KOMPILE NATIVE IMAGE
# ═══════════════════════════════════════════════════════════════════════════════

# Build everything for a given DL4J platform string:
#   1. Build DL4J backend (installs nd4j-* JARs to local Maven repo)
#   2. Collect SDX runtime SDK bindings from DL4J build output
#   3. Build kompile Java modules (picks up the nd4j-* JARs)
#   4. Build kompile native images
#   5. Assemble distribution tarball (includes SDX artifacts)
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

  log "START: kompile build for ${platform}"
  local start_time; start_time=$(date +%s)

  mkdir -p "${KOMPILE_OUTPUT_DIR}"

  # Ensure repos are cloned and on the right branch
  kompile_ensure_kompile
  if [ "${skip_dl4j}" -eq 0 ]; then
    kompile_ensure_dl4j
    # Re-source DL4J build-common if it wasn't loaded earlier (first clone)
    if [ "${_DL4J_COMMON_LOADED}" -ne 1 ] && [ -f "${DL4J_PROJECT_ROOT}/build-scripts/build-common.sh" ]; then
      PROJECT_ROOT="${DL4J_PROJECT_ROOT}" source "${DL4J_PROJECT_ROOT}/build-scripts/build-common.sh"
      _DL4J_COMMON_LOADED=1
    fi
  fi

  # Resolve backend type
  local backend_type cuda_version nd4j_artifact
  read -r backend_type cuda_version nd4j_artifact < <(_resolve_backend_from_platform "$platform")

  local extra_mvn_flags=""
  if [ "${backend_type}" = "cuda" ]; then
    extra_mvn_flags="-Dnd4j.backend=${nd4j_artifact} -Dkompile.cuda=true"
  fi

  # Step 1: Build DL4J backend
  if [ "${skip_dl4j}" -eq 0 ]; then
    log "Step 1/5: Building DL4J backend (${platform})"
    kompile_build_dl4j_backend "${platform}" || return 1
  else
    log "Step 1/5: Skipped DL4J backend build"
  fi

  # Step 2: Collect SDX runtime SDK bindings
  if [ "${skip_dl4j}" -eq 0 ] && [ -n "${DL4J_PROJECT_ROOT:-}" ]; then
    log "Step 2/5: Collecting SDX bindings (${platform})"
    kompile_collect_sdx_bindings "${platform}"
  else
    log "Step 2/5: Skipped SDX collection (DL4J build skipped)"
  fi

  # Step 3: Build kompile Java modules
  if [ "${skip_java}" -eq 0 ]; then
    log "Step 3/5: Building kompile Java modules"
    kompile_build_java_modules "${extra_mvn_flags}" || return 1
  else
    log "Step 3/5: Skipped kompile Java build"
  fi

  # Step 4: Build native images
  if [ "${skip_native}" -eq 0 ]; then
    log "Step 4/5: Building native images (${NATIVE_TARGETS})"
    kompile_build_all_native "${extra_mvn_flags}" || return 1
  else
    log "Step 4/5: Skipped native image build"
  fi

  # Step 5: Assemble distribution
  if [ "${skip_dist}" -eq 0 ]; then
    log "Step 5/5: Assembling distribution"
    kompile_assemble_dist "${platform}" || return 1
  else
    log "Step 5/5: Skipped distribution assembly"
  fi

  local end_time; end_time=$(date +%s)
  local elapsed=$(( end_time - start_time ))
  log "DONE: kompile build for ${platform} (${elapsed}s)"
}
