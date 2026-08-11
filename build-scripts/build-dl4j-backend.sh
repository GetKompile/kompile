#!/usr/bin/env bash
###############################################################################
# build-dl4j-backend.sh
#
# Build (or reuse) a DL4J backend and install it to the local Maven repo so
# kompile can consume it via -Dnd4j.version=<version> [-Dkompile.backend=<variant>].
#
# USAGE
#   build-dl4j-backend.sh [OPTIONS]
#
# OPTIONS
#   --dl4j-dir <path>          Path to deeplearning4j checkout
#                              (default: ../deeplearning4j relative to repo root)
#   --chip cpu|cuda            Backend chip type (default: cpu)
#   --cuda-version 12.6|12.9   CUDA version; only with --chip cuda (default: 12.9)
#   --helper <list>            Comma-separated helpers: onednn,cudnn,armcompute,mlir
#   --extension <ext>          javacpp.platform.extension suffix (avx2|avx512|...)
#   --compile                  Shorthand: --helper mlir plus MLIR/Triton JIT flags
#                              (-Dlibnd4j.triton=ON, extension=-compile)
#   --pin <version>            Pin DL4J to <version> via update-versions.sh before
#                              building (e.g. 1.0.0-kompile-SNAPSHOT). Does NOT
#                              auto-restore; prints the restore command after build.
#   --libnd4j-home <path>      Reuse a prebuilt libnd4j C++ tree (skips C++ build)
#   --skip-cpp                 Build only nd4j-*-preset and nd4j-* (skip :libnd4j)
#   --jobs <N>                 Parallel C++ compilation threads (default: 12)
#   --maven-repo-local <path>  Install into an isolated Maven local repository
#   --dry-run                  Print the mvn command and exit without running it
#   -h, --help                 Show this help and exit
#
# EXAMPLES
#   # 1. Plain CPU build (nd4j-native, version stays 1.0.0-SNAPSHOT)
#   ./build-scripts/build-dl4j-backend.sh --chip cpu
#
#   # 2. CUDA 12.9 with cuDNN helper
#   ./build-scripts/build-dl4j-backend.sh --chip cuda --cuda-version 12.9 --helper cudnn
#
#   # 3. CPU pinned to a custom version with OneDNN + AVX-512
#   ./build-scripts/build-dl4j-backend.sh \
#     --chip cpu --helper onednn --extension avx512 \
#     --pin 1.0.0-kompile-SNAPSHOT
#
# After a successful build, consume the result from kompile:
#   mvn clean install -Dnd4j.version=<version> -Dkompile.backend=<variant>
###############################################################################
set -euo pipefail

# ─── Locate script / repo roots ──────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOMPILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ─── MVN resolution ──────────────────────────────────────────────────────────
# Prefer $MVN env, then the well-known user dev-apps location, then PATH.
if [ -z "${MVN:-}" ]; then
  if [ -x "/home/agibsonccc/dev-apps/mvn/bin/mvn" ]; then
    MVN="/home/agibsonccc/dev-apps/mvn/bin/mvn"
  else
    MVN="mvn"
  fi
fi

# ─── Defaults ────────────────────────────────────────────────────────────────
DL4J_DIR=""
CHIP="cpu"
CUDA_VERSION="12.9"
HELPERS=""
EXTENSION=""
DO_COMPILE=0
PIN_VERSION=""
LIBND4J_HOME=""
SKIP_CPP=0
JOBS="${BUILD_THREADS:-12}"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-}"
CCACHE_BIN="${CCACHE_BIN:-$(command -v ccache || true)}"
DRY_RUN=0

# ─── Logging ─────────────────────────────────────────────────────────────────
log() { echo "=== [$(date '+%Y-%m-%d %H:%M:%S')] $* ==="; }
die() { echo "ERROR: $*" >&2; exit 1; }

# ─── Help ────────────────────────────────────────────────────────────────────
usage() {
  sed -n '/^# USAGE/,/^###/p' "$0" | grep -v '^###' | sed 's/^# \{0,1\}//'
  exit 0
}

# ─── Argument parsing ────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dl4j-dir)      DL4J_DIR="$2";       shift 2 ;;
    --chip)          CHIP="$2";            shift 2 ;;
    --cuda-version)  CUDA_VERSION="$2";    shift 2 ;;
    --helper)        HELPERS="$2";         shift 2 ;;
    --extension)     EXTENSION="$2";       shift 2 ;;
    --compile)       DO_COMPILE=1;         shift   ;;
    --pin)           PIN_VERSION="$2";     shift 2 ;;
    --libnd4j-home)  LIBND4J_HOME="$2";    shift 2 ;;
    --skip-cpp)      SKIP_CPP=1;           shift   ;;
    --jobs)          JOBS="$2";            shift 2 ;;
    --maven-repo-local) MAVEN_REPO_LOCAL="$2"; shift 2 ;;
    --dry-run)       DRY_RUN=1;            shift   ;;
    -h|--help)       usage ;;
    *) die "Unknown option: $1. Run with --help for usage." ;;
  esac
done

# ─── Resolve DL4J dir (absolute) ─────────────────────────────────────────────
if [ -z "${DL4J_DIR}" ]; then
  DL4J_DIR="$(cd "${KOMPILE_ROOT}/../deeplearning4j" 2>/dev/null && pwd \
    || echo "${KOMPILE_ROOT}/../deeplearning4j")"
fi
# Make absolute without requiring the dir to already exist
if [[ "${DL4J_DIR}" != /* ]]; then
  DL4J_DIR="$(cd "${KOMPILE_ROOT}" && cd "${DL4J_DIR}" && pwd)"
fi

[ -d "${DL4J_DIR}" ] || die "DL4J directory not found: ${DL4J_DIR}  (use --dl4j-dir)"
[ -n "${CCACHE_BIN}" ] && [ -x "${CCACHE_BIN}" ] \
  || die "ccache is required for native DL4J builds; set CCACHE_BIN to its executable"

log "DL4J dir: ${DL4J_DIR}"
log "Chip:     ${CHIP}"

# ─── Validate chip ────────────────────────────────────────────────────────────
case "${CHIP}" in
  cpu|cuda) ;;
  *) die "Unknown --chip value '${CHIP}'. Must be cpu or cuda." ;;
esac

# ─── --compile shorthand ──────────────────────────────────────────────────────
if [ "${DO_COMPILE}" -eq 1 ]; then
  # merge 'mlir' into the helpers list
  if [ -z "${HELPERS}" ]; then
    HELPERS="mlir"
  elif [[ "${HELPERS}" != *mlir* ]]; then
    HELPERS="${HELPERS},mlir"
  fi
  EXTENSION="-compile"   # full classifier value (note: leading dash)
fi

# ─── Determine Maven profile and nd4j artifact ───────────────────────────────
if [ "${CHIP}" = "cuda" ]; then
  MVN_PROFILE="cuda"
  ND4J_ARTIFACT="nd4j-cuda-${CUDA_VERSION}"
  ND4J_PRESET="nd4j-cuda-${CUDA_VERSION}-preset"
  KOMPILE_BACKEND_HINT="cuda-${CUDA_VERSION}"
else
  MVN_PROFILE="cpu"
  ND4J_ARTIFACT="nd4j-native"
  ND4J_PRESET="nd4j-native-preset"
  KOMPILE_BACKEND_HINT="cpu"
fi

# ─── Pin version ──────────────────────────────────────────────────────────────
INSTALL_VERSION=""
if [ -n "${PIN_VERSION}" ]; then
  # Parse current version from root pom <version> tag
  CURRENT_VERSION="$(grep -m1 '<version>' "${DL4J_DIR}/pom.xml" \
    | sed 's|.*<version>\(.*\)</version>.*|\1|' | tr -d ' \t')"
  [ -n "${CURRENT_VERSION}" ] || die "Could not parse <version> from ${DL4J_DIR}/pom.xml"
  log "Pinning DL4J version: ${CURRENT_VERSION} -> ${PIN_VERSION}"
  if [ "${DRY_RUN}" -eq 0 ]; then
    cd "${DL4J_DIR}"
    bash update-versions.sh "${CURRENT_VERSION}" "${PIN_VERSION}"
    cd "${KOMPILE_ROOT}"
  else
    echo "[DRY-RUN] cd ${DL4J_DIR} && bash update-versions.sh ${CURRENT_VERSION} ${PIN_VERSION}"
  fi
  INSTALL_VERSION="${PIN_VERSION}"
else
  INSTALL_VERSION="$(grep -m1 '<version>' "${DL4J_DIR}/pom.xml" \
    | sed 's|.*<version>\(.*\)</version>.*|\1|' | tr -d ' \t')"
fi

log "Installing nd4j version: ${INSTALL_VERSION}"

# Isolate mutable SNAPSHOT artifacts by source revision unless the caller explicitly selects a
# repository. This prevents a fresh nd4j-api from being combined with an older native binding.
DL4J_REVISION="$(git -C "${DL4J_DIR}" rev-parse --short=12 HEAD)"
if [ -z "${MAVEN_REPO_LOCAL}" ]; then
  MAVEN_REPO_LOCAL="${KOMPILE_ROOT}/.kompile/m2/dl4j-${DL4J_REVISION}-${CHIP}"
fi
log "DL4J revision: ${DL4J_REVISION}"
log "Maven repo:    ${MAVEN_REPO_LOCAL}"

# ─── Build the module list ────────────────────────────────────────────────────
if [ "${SKIP_CPP}" -eq 1 ] || [ -n "${LIBND4J_HOME}" ]; then
  PL_MODULES=":nd4j-api,:${ND4J_PRESET},:${ND4J_ARTIFACT}"
else
  PL_MODULES=":nd4j-api,:libnd4j,:${ND4J_PRESET},:${ND4J_ARTIFACT}"
fi

# ─── Compose -Dlibnd4j.* flags ───────────────────────────────────────────────
# The native build verifies this contract after CMake configuration. Passing it
# explicitly prevents an otherwise valid auto-detected ccache setup from being rejected.
LIBND4J_CMAKE_ARGS="-DCMAKE_C_COMPILER_LAUNCHER:FILEPATH=${CCACHE_BIN} -DCMAKE_CXX_COMPILER_LAUNCHER:FILEPATH=${CCACHE_BIN} -DSD_REQUIRE_COMPILER_CACHE=ON -DSD_EXPECTED_COMPILER_CACHE:FILEPATH=${CCACHE_BIN}"
LIBND4J_ARGS=(
  "-Dlibnd4j.triton=ON"
  "-Dlibnd4j.log=libnd4j-build.log"
)

# Helper flag (comma → space-separated for the -D value DL4J expects)
if [ -n "${HELPERS}" ]; then
  # DL4J expects -Dlibnd4j.helper=onednn or comma-separated list
  LIBND4J_ARGS+=("-Dlibnd4j.helper=${HELPERS}")
fi

# Extension (avx2, avx512, etc.)
if [ -n "${EXTENSION}" ]; then
  # Strip leading dash for libnd4j.extension; keep full value for javacpp.platform.extension
  EXT_BARE="${EXTENSION#-}"
  LIBND4J_ARGS+=(
    "-Dlibnd4j.extension=${EXT_BARE}"
    "-Djavacpp.platform.extension=${EXTENSION}"
  )
fi

# CUDA-specific flags
if [ "${CHIP}" = "cuda" ]; then
  LIBND4J_ARGS+=("-Dlibnd4j.chip=cuda" "-Dlibnd4j.cuda.version=${CUDA_VERSION}")
fi

# Prebuilt C++ tree
if [ -n "${LIBND4J_HOME}" ]; then
  LIBND4J_ARGS+=("-DLIBND4J_HOME=${LIBND4J_HOME}")
fi

# Parallel jobs
LIBND4J_ARGS+=("-Dlibnd4j.buildthreads=${JOBS}")

# ─── Compose the full mvn command ─────────────────────────────────────────────
MVN_CMD=(
  "${MVN}" "-P${MVN_PROFILE}" clean install -DskipTests -am
  -pl "${PL_MODULES}"
  "${LIBND4J_ARGS[@]}"
)
if [ -n "${MAVEN_REPO_LOCAL}" ]; then
  MVN_CMD+=("-Dmaven.repo.local=${MAVEN_REPO_LOCAL}")
fi
MVN_CMD+=(--batch-mode --no-transfer-progress)
printf -v MVN_CMD_DISPLAY ' %q' "${MVN_CMD[@]}"
MVN_CMD_DISPLAY="${MVN_CMD_DISPLAY# }"

CONSUME_CMD=(
  mvn clean install
  "-Dnd4j.version=${INSTALL_VERSION}"
  "-Dkompile.backend=${KOMPILE_BACKEND_HINT}"
)
if [ -n "${MAVEN_REPO_LOCAL}" ]; then
  CONSUME_CMD+=("-Dmaven.repo.local=${MAVEN_REPO_LOCAL}")
fi
printf -v CONSUME_CMD_DISPLAY ' %q' "${CONSUME_CMD[@]}"
CONSUME_CMD_DISPLAY="${CONSUME_CMD_DISPLAY# }"

# ─── Print or run ─────────────────────────────────────────────────────────────
if [ "${DRY_RUN}" -eq 1 ]; then
  echo ""
  echo "========== DRY-RUN: would execute from ${DL4J_DIR} =========="
  echo "  CMAKE_ARGUMENTS=${LIBND4J_CMAKE_ARGS@Q} DL4J_COMPILER_CACHE=${CCACHE_BIN@Q} ${MVN_CMD_DISPLAY}"
  echo "================================================================"
  echo ""
  echo "Kompile consumption line:"
  echo "  ${CONSUME_CMD_DISPLAY}"
  exit 0
fi

log "Building DL4J backend from: ${DL4J_DIR}"
log "Command: CMAKE_ARGUMENTS=${LIBND4J_CMAKE_ARGS@Q} DL4J_COMPILER_CACHE=${CCACHE_BIN@Q} ${MVN_CMD_DISPLAY}"

cd "${DL4J_DIR}"
CMAKE_ARGUMENTS="${LIBND4J_CMAKE_ARGS}" \
DL4J_COMPILER_CACHE="${CCACHE_BIN}" \
"${MVN_CMD[@]}"
BUILD_RC=$?
cd "${KOMPILE_ROOT}"

if [ "${BUILD_RC}" -ne 0 ]; then
  die "DL4J backend build failed (exit ${BUILD_RC})"
fi

# Verify the packaged Java binding overrides the exact NativeOps ABI that SameDiff calls.
ARTIFACT_BASE="${MAVEN_REPO_LOCAL}/org/eclipse/deeplearning4j"
BACKEND_JAR="${ARTIFACT_BASE}/${ND4J_ARTIFACT}/${INSTALL_VERSION}/${ND4J_ARTIFACT}-${INSTALL_VERSION}.jar"
if [ "${CHIP}" = "cuda" ]; then
  BINDING_CLASS="org.nd4j.linalg.jcublas.bindings.Nd4jCuda"
else
  BINDING_CLASS="org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu"
fi
[ -f "${BACKEND_JAR}" ] || die "Packaged backend jar not found: ${BACKEND_JAR}"
ABI_OUTPUT="$(javap -classpath "${BACKEND_JAR}" "${BINDING_CLASS}")"
EXPECTED_DISPATCH_ABI="  public native org.bytedeco.javacpp.Pointer dispatchNativePlan(org.bytedeco.javacpp.Pointer, org.bytedeco.javacpp.Pointer, long, org.bytedeco.javacpp.Pointer, long, org.bytedeco.javacpp.Pointer, long, int, int);"
ABI_MATCH_COUNT="$(grep -Fxc "${EXPECTED_DISPATCH_ABI}" <<<"${ABI_OUTPUT}" || true)"
if [ "${ABI_MATCH_COUNT}" -ne 1 ]; then
  die "${BINDING_CLASS} does not declare exactly one current 9-argument dispatchNativePlan ABI"
fi

BUILD_MANIFEST="${MAVEN_REPO_LOCAL}/dl4j-build-manifest.txt"
{
  echo "revision=${DL4J_REVISION}"
  echo "version=${INSTALL_VERSION}"
  echo "chip=${CHIP}"
  echo "backend=${ND4J_ARTIFACT}"
  echo "preset=${ND4J_PRESET}"
  echo "dispatchAbi=9"
  find "${ARTIFACT_BASE}" -type f -path "*/${INSTALL_VERSION}/*" -print0 \
    | sort -z | xargs -0 sha256sum
} >"${BUILD_MANIFEST}"
log "Wrote aligned artifact manifest: ${BUILD_MANIFEST}"

log "DL4J backend build SUCCEEDED — installed ${INSTALL_VERSION} to ${MAVEN_REPO_LOCAL:-the active Maven local repository}"

# ─── Post-build: pin restore reminder ────────────────────────────────────────
if [ -n "${PIN_VERSION}" ]; then
  echo ""
  echo "##################################################################"
  echo "#  VERSION PINNED — DO NOT FORGET TO RESTORE"
  echo "#"
  echo "#  DL4J was pinned from ${CURRENT_VERSION} -> ${PIN_VERSION}."
  echo "#  The sources in ${DL4J_DIR} still carry the pinned version."
  echo "#  To restore, run:"
  echo "#"
  echo "#    cd ${DL4J_DIR}"
  echo "#    bash update-versions.sh ${PIN_VERSION} ${CURRENT_VERSION}"
  echo "#"
  echo "##################################################################"
  echo ""
fi

# ─── Consumption line ─────────────────────────────────────────────────────────
echo ""
echo "========== NEXT: consume from kompile =========="
echo ""
echo "  ${MVN} clean install \\"
echo "    -Dnd4j.version=${INSTALL_VERSION} \\"
if [ -n "${MAVEN_REPO_LOCAL}" ]; then
  echo "    -Dmaven.repo.local=${MAVEN_REPO_LOCAL} \\"
fi
echo "    -Dkompile.backend=${KOMPILE_BACKEND_HINT}"
echo ""
echo "================================================="
