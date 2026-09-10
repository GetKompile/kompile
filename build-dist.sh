#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Kompile Distribution Builder
#
# Builds platform-specific distribution archives containing native binaries or JVM
# executable JARs, depending on the selected packaging mode.
#
# Usage:
#   ./build-dist.sh <variant> [options]
#
# Variants:
#   cli-only      — CLI plus optional backend-bearing local serving JARs
#   local         — CLI + native local execution workers; no staging/web/batch server
#   full          — CLI native + all service exec JARs + bundled Java runtime
#   hosted        — CLI + app-main + staging (for API-key/hosted LLM users)
#   cpu-intel     — CLI + app-main + staging with nd4j-native x86_64
#   cpu-arm       — CLI + app-main + staging with nd4j-native aarch64
#   cuda          — CLI + app-main + staging with nd4j-cuda
#   amd-zluda     — CLI + app-main + staging with ZLUDA (ROCm 7.2.4 default)
#
# Options:
#   --skip-java-build    Skip the Maven Java install (use existing target/)
#   --skip-native        Skip native image compilation (use existing binaries)
#   --jars-only          Use exec JARs instead of native images (implies --skip-native)
#   --parallel N         Number of parallel native-image builds (default: 1)
#   --output-dir DIR     Where to write the final .zip and .tar.gz (default: ./dist/)
#   --version VER        Version string (default: from pom.xml)
#   --platform PLATFORM  Maven/JavaCPP platform classifier (default: detected host)
#   --sdx-assets DIR     DL4J SDK assets (runtime packages plus jars/) for backend variants
#   --cuda-version VER   CUDA artifact line for the cuda variant: 12.6 or 12.9 (default: 12.9)
#   --backend-profile P  Exact root-POM backend profile (normally supplied by build-common.sh)
#   --sdk-classifier C   Exact DL4J SDK/native classifier (normally derived from the profile)
#   --distribution-classifier C  Unique Maven/archive classifier (variant plus release lane)
#   --skip-maven-install Do not install the classified ZIP in the local Maven repository
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"

# All shell-owned paths use the MSYS namespace. Native Maven/Java arguments
# retain a separately converted Windows path where required.
# shellcheck source=build-scripts/path-normalization.sh
_PATH_HELPER="${SCRIPT_DIR}/build-scripts/path-normalization.sh"
if [ -f "${_PATH_HELPER}" ]; then
    source "${_PATH_HELPER}"
else
    # Test harnesses may copy this standalone entrypoint without the source tree.
    # Production distributions include build-scripts/path-normalization.sh.
    kompile_windows_shell() {
        case "${OSTYPE:-}:${MSYSTEM:-}" in
            cygwin*|msys*|win32*|*:MSYS*|*:MINGW*) return 0 ;;
            *) return 1 ;;
        esac
    }
    if kompile_windows_shell; then
        echo "ERROR: build-dist.sh requires build-scripts/path-normalization.sh on Windows/MSYS" >&2
        exit 1
    fi
    kompile_path_to_posix() { printf '%s\n' "${1:-}"; }
    kompile_path_to_native() { printf '%s\n' "${1:-}"; }
    kompile_normalize_shell_paths() { :; }
fi

# ── Configuration ────────────────────────────────────────────────────────────

GRAALVM_HOME="${GRAALVM_HOME:-${HOME}/.sdkman/candidates/java/21.0.10-graal}"
GRAALVM_HOME="$(kompile_path_to_posix "${GRAALVM_HOME}")"
# Maven: env override > PATH > local dev fallback (keeps clones working without edits)
if [ -z "${MVN:-}" ]; then
    MVN="$(command -v mvn 2>/dev/null || echo /home/agibsonccc/dev-apps/mvn/bin/mvn)"
fi
JAVA_HOME="${GRAALVM_HOME}"
export JAVA_HOME

VARIANT="${1:-}"
SKIP_JAVA_BUILD=false
SKIP_NATIVE=false
JARS_ONLY=false
PARALLEL=1
OUTPUT_DIR="${SCRIPT_DIR}/dist"
VERSION=""
PLATFORM_OVERRIDE=""
SDX_ASSETS_DIR="${KOMPILE_SDX_ASSETS_DIR:-}"
CUDA_VERSION="${KOMPILE_CUDA_VERSION:-12.9}"
INSTALL_MAVEN=true
BACKEND_PROFILE_OVERRIDE=""
SDK_CLASSIFIER_OVERRIDE=""
DISTRIBUTION_CLASSIFIER_OVERRIDE=""

shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-java-build)  SKIP_JAVA_BUILD=true; shift ;;
        --skip-native)      SKIP_NATIVE=true; shift ;;
        --jars-only)        JARS_ONLY=true; SKIP_NATIVE=true; shift ;;
        --parallel)         PARALLEL="$2"; shift 2 ;;
        --output-dir)       OUTPUT_DIR="$2"; shift 2 ;;
        --version)          VERSION="$2"; shift 2 ;;
        --platform)         PLATFORM_OVERRIDE="$2"; shift 2 ;;
        --sdx-assets)       SDX_ASSETS_DIR="$2"; shift 2 ;;
        --cuda-version)      CUDA_VERSION="$2"; shift 2 ;;
        --backend-profile)   BACKEND_PROFILE_OVERRIDE="$2"; shift 2 ;;
        --sdk-classifier)    SDK_CLASSIFIER_OVERRIDE="$2"; shift 2 ;;
        --distribution-classifier) DISTRIBUTION_CLASSIFIER_OVERRIDE="$2"; shift 2 ;;
        --skip-maven-install) INSTALL_MAVEN=false; shift ;;
        *)                  echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

case "${PARALLEL}" in
    ''|*[!0-9]*|0)
        echo "--parallel requires a positive integer (got '${PARALLEL}')" >&2
        exit 1
        ;;
esac

if [ -z "${VARIANT}" ]; then
    echo "Usage: $0 <variant> [options]"
    echo ""
    echo "Variants: cli-only, local, full, hosted, cpu-intel, cpu-arm, cuda, amd-zluda"
    exit 1
fi

# Arguments arrive here from PowerShell/Python as native Windows paths on the
# Azure worker. Convert values used by Bash before any mkdir/find/cp/tar/Python
# operation. The Maven-native values are derived separately below.
kompile_normalize_shell_paths
OUTPUT_DIR="$(kompile_path_to_posix "${OUTPUT_DIR}")"
if [ -n "${SDX_ASSETS_DIR}" ]; then
    SDX_ASSETS_DIR="$(kompile_path_to_posix "${SDX_ASSETS_DIR}")"
fi
JAVA_HOME="${GRAALVM_HOME}"
export JAVA_HOME

# Resolve version from pom if not specified
if [ -z "${VERSION}" ]; then
    VERSION=$(grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')
fi

# Detect platform
case "$(uname -s)" in
    Linux*)  OS="linux" ;;
    Darwin*) OS="macosx" ;;
    *)       OS="windows" ;;
esac
case "$(uname -m)" in
    x86_64|amd64)   ARCH="x86_64" ;;
    aarch64|arm64)  ARCH="arm64" ;;
    *)              ARCH="$(uname -m)" ;;
esac
PLATFORM="${PLATFORM_OVERRIDE:-${OS}-${ARCH}}"
EXE_SUFFIX=""
case "${PLATFORM}" in
    windows-*) EXE_SUFFIX=".exe" ;;
esac

echo "════════════════════════════════════════════════════════════════"
echo " Kompile Distribution Builder"
echo "════════════════════════════════════════════════════════════════"
echo "  Variant:   ${VARIANT}"
echo "  Platform:  ${PLATFORM}"
echo "  Version:   ${VERSION}"
echo "  GraalVM:   ${GRAALVM_HOME}"
echo "  Output:    ${OUTPUT_DIR}"
echo "  Parallel:  ${PARALLEL}"
echo "  SDX assets: ${SDX_ASSETS_DIR:-<not required>}"
echo ""

# ── Variant configuration ────────────────────────────────────────────────────

# What to build for each variant
CLI_NATIVE=true            # Native CLI unless --jars-only selects the JVM tier
APP_NATIVE=false           # kompile-app-main native
STAGING_NATIVE=false       # kompile-model-staging native
LOCAL_RUNTIME=false        # request-scoped model/pipeline serving artifacts
SERVER_JARS_ONLY=false     # package service JARs instead of service images
BUNDLE_RUNTIME=true        # jlink runtime for JVM fallback/product services
INCLUDE_CLI_JAR=true       # shaded CLI/JBang fallback
INCLUDE_PRODUCT_EXTRAS=true # web personas, SDK server, C/Python bindings, app config
ND4J_BACKEND=""            # Java backend artifact; empty = no local models
KOMPILE_BACKEND_PROFILE="" # exact root-POM profile matching the DL4J classifier matrix
CUDA_FLAG=""               # compatibility switch used by downstream native-image configuration
EXTRA_MVN_FLAGS=""

case "${VARIANT}" in
    cli-only)
        APP_NATIVE=false
        STAGING_NATIVE=false
        BUNDLE_RUNTIME=false
        INCLUDE_CLI_JAR=false
        INCLUDE_PRODUCT_EXTRAS=false
        ;;
    local)
        # Native folder-local execution only. Local crawl is a hidden mode of the
        # CLI itself; provisioning/staging is an explicit workflow, not part of
        # the default inference closure.
        APP_NATIVE=false
        STAGING_NATIVE=false
        BUNDLE_RUNTIME=false
        INCLUDE_CLI_JAR=false
        INCLUDE_PRODUCT_EXTRAS=false
        ND4J_BACKEND="nd4j-native"
        KOMPILE_BACKEND_PROFILE="cpu"
        ;;
    full)
        APP_NATIVE=true
        STAGING_NATIVE=true
        ND4J_BACKEND="nd4j-native"
        KOMPILE_BACKEND_PROFILE="cpu"
        ;;
    hosted)
        APP_NATIVE=true
        STAGING_NATIVE=true
        # No ND4J backend — uses API keys for embeddings + LLM
        ;;
    cpu-intel)
        APP_NATIVE=true
        STAGING_NATIVE=true
        ND4J_BACKEND="nd4j-native"
        KOMPILE_BACKEND_PROFILE="cpu-avx2"
        EXTRA_MVN_FLAGS="-Dlibnd4j.extension=avx2"
        ;;
    cpu-arm)
        APP_NATIVE=true
        STAGING_NATIVE=true
        ND4J_BACKEND="nd4j-native"
        KOMPILE_BACKEND_PROFILE="cpu"
        ;;
    cuda)
        APP_NATIVE=true
        STAGING_NATIVE=true
        case "${CUDA_VERSION}" in
            12.6|12.9) ;;
            *) echo "Unsupported CUDA version: ${CUDA_VERSION} (expected 12.6 or 12.9)" >&2; exit 1 ;;
        esac
        ND4J_BACKEND="nd4j-cuda-${CUDA_VERSION}"
        KOMPILE_BACKEND_PROFILE="cuda-${CUDA_VERSION}"
        CUDA_FLAG="-Dkompile.cuda=true"
        ;;
    amd-zluda)
        APP_NATIVE=true
        STAGING_NATIVE=true
        ND4J_BACKEND="nd4j-zluda-12.9"
        KOMPILE_BACKEND_PROFILE="zluda-rocm-7.2.4"
        CUDA_FLAG="-Dkompile.cuda=true"
        EXTRA_MVN_FLAGS="-Dkompile.zluda=true"
        ;;
    *)
        echo "Unknown variant: ${VARIANT}" >&2
        echo "Valid: cli-only, local, full, hosted, cpu-intel, cpu-arm, cuda, amd-zluda" >&2
        exit 1
        ;;
esac

# Source-driven release builds pass all three identities explicitly. Direct
# canonical invocations retain useful defaults without collapsing CUDA lines or
# CPU helper classifiers.
if [ -n "${BACKEND_PROFILE_OVERRIDE}" ]; then
    KOMPILE_BACKEND_PROFILE="${BACKEND_PROFILE_OVERRIDE}"
    CUDA_FLAG=""
    EXTRA_MVN_FLAGS=""
    case "${KOMPILE_BACKEND_PROFILE}" in
        cuda-12.6*) ND4J_BACKEND="nd4j-cuda-12.6"; CUDA_VERSION="12.6"; CUDA_FLAG="-Dkompile.cuda=true" ;;
        cuda-12.9*) ND4J_BACKEND="nd4j-cuda-12.9"; CUDA_VERSION="12.9"; CUDA_FLAG="-Dkompile.cuda=true" ;;
        zluda) ND4J_BACKEND="nd4j-zluda"; CUDA_VERSION="12.9"; CUDA_FLAG="-Dkompile.cuda=true"; EXTRA_MVN_FLAGS="-Dkompile.zluda=true" ;;
        zluda-rocm-7.2.4|zluda-rocm-10.0.0) ND4J_BACKEND="nd4j-zluda-12.9"; CUDA_VERSION="12.9"; CUDA_FLAG="-Dkompile.cuda=true"; EXTRA_MVN_FLAGS="-Dkompile.zluda=true" ;;
        vulkan*) ND4J_BACKEND="nd4j-vulkan" ;;
        hexagon) ND4J_BACKEND="nd4j-hexagon" ;;
        tpu) ND4J_BACKEND="nd4j-tpu" ;;
        cpu*) ND4J_BACKEND="nd4j-native" ;;
        *) echo "Unsupported backend profile: ${KOMPILE_BACKEND_PROFILE}" >&2; exit 1 ;;
    esac
fi
if [ "${KOMPILE_BACKEND_PROFILE}" = "zluda-rocm-10.0.0" ] \
        && [ "${PLATFORM}" != "linux-x86_64" ]; then
    echo "ROCm 10 ZLUDA distributions are supported only on linux-x86_64 (got ${PLATFORM})" >&2
    exit 1
fi
# Local request-scoped runtimes require a packaged numerical backend. A cli-only
# build remains remote-only unless a backend profile is selected explicitly; with
# one selected it carries the same model/pipeline worker closure in the chosen form.
if [ -n "${ND4J_BACKEND}" ]; then
    LOCAL_RUNTIME=true
fi

# The JAR tier is a complete JVM distribution boundary. Native images are not
# built or copied, while the existing shaded/exec JARs remain addressable by the
# same component names through ComponentRegistry.
if [ "${JARS_ONLY}" = true ]; then
    CLI_NATIVE=false
    SERVER_JARS_ONLY=true
    INCLUDE_CLI_JAR=true
fi

if [ -n "${SDK_CLASSIFIER_OVERRIDE}" ]; then
    SDK_CLASSIFIER="${SDK_CLASSIFIER_OVERRIDE}"
else
    case "${KOMPILE_BACKEND_PROFILE}" in
        ""|cpu|cuda-12.6|cuda-12.9|vulkan|hexagon|tpu) SDK_CLASSIFIER="${PLATFORM}" ;;
        cpu-*) SDK_CLASSIFIER="${PLATFORM}-${KOMPILE_BACKEND_PROFILE#cpu-}" ;;
        cuda-12.6-cudnn|cuda-12.9-cudnn) SDK_CLASSIFIER="${PLATFORM}-cudnn" ;;
        cuda-12.6-compile|cuda-12.9-compile|vulkan-compile) SDK_CLASSIFIER="${PLATFORM}-compile" ;;
        zluda) SDK_CLASSIFIER="${PLATFORM}-zluda" ;;
        zluda-rocm-*) SDK_CLASSIFIER="${PLATFORM}-${KOMPILE_BACKEND_PROFILE}" ;;
        *) echo "Cannot derive SDK classifier for backend profile: ${KOMPILE_BACKEND_PROFILE}" >&2; exit 1 ;;
    esac
fi
case "${SDK_CLASSIFIER}" in
    *[!A-Za-z0-9._-]*|'') echo "Invalid SDK classifier: ${SDK_CLASSIFIER}" >&2; exit 1 ;;
    "${PLATFORM}"|"${PLATFORM}"-*) ;;
    *) echo "SDK classifier '${SDK_CLASSIFIER}' does not belong to platform '${PLATFORM}'" >&2; exit 1 ;;
esac
if [ -n "${DISTRIBUTION_CLASSIFIER_OVERRIDE}" ]; then
    DISTRIBUTION_CLASSIFIER="${DISTRIBUTION_CLASSIFIER_OVERRIDE}"
else
    case "${KOMPILE_BACKEND_PROFILE}" in
        ""|cpu) RELEASE_LANE_CLASSIFIER="${PLATFORM}" ;;
        cpu-*) RELEASE_LANE_CLASSIFIER="${PLATFORM}-${KOMPILE_BACKEND_PROFILE#cpu-}" ;;
        cuda-*) RELEASE_LANE_CLASSIFIER="${PLATFORM}-${KOMPILE_BACKEND_PROFILE}" ;;
        zluda) RELEASE_LANE_CLASSIFIER="${PLATFORM}-cuda-12.9-zluda" ;;
        zluda-rocm-*) RELEASE_LANE_CLASSIFIER="${PLATFORM}-cuda-12.9-${KOMPILE_BACKEND_PROFILE}" ;;
        vulkan|vulkan-compile|hexagon|tpu) RELEASE_LANE_CLASSIFIER="${PLATFORM}-${KOMPILE_BACKEND_PROFILE}" ;;
        *) echo "Cannot derive distribution classifier for backend profile: ${KOMPILE_BACKEND_PROFILE}" >&2; exit 1 ;;
    esac
    DISTRIBUTION_CLASSIFIER="${VARIANT}-${RELEASE_LANE_CLASSIFIER}"
fi
case "${DISTRIBUTION_CLASSIFIER}" in
    *[!A-Za-z0-9._-]*|'') echo "Invalid distribution classifier: ${DISTRIBUTION_CLASSIFIER}" >&2; exit 1 ;;
esac

MAVEN_REPOSITORY_INPUT="${KOMPILE_MAVEN_REPO:-${HOME}/.m2/repository}"
MAVEN_REPOSITORY="$(kompile_path_to_native "${MAVEN_REPOSITORY_INPUT}")"
MAVEN_REPOSITORY_SHELL="$(kompile_path_to_posix "${MAVEN_REPOSITORY}")"
ND4J_VERSION="${ND4J_VERSION:-1.0.0-SNAPSHOT}"
DL4J_MAVEN_REPOSITORY_URL="${DL4J_MAVEN_REPOSITORY_URL:-}"
DL4J_MAVEN_REPOSITORY_ID="${DL4J_MAVEN_REPOSITORY_ID:-dl4j-release}"

# These arguments must reach EVERY Maven invocation (reactor, exec-JAR, and
# native-image builds). Arrays preserve repository URLs and local paths exactly
# and avoid reparsing user-provided values through eval.
MAVEN_BUILD_ARGS=(
    "--no-snapshot-updates"
    "-Dmaven.repo.local=${MAVEN_REPOSITORY}"
    "-Dnd4j.version=${ND4J_VERSION}"
)
if [ -n "${DL4J_MAVEN_REPOSITORY_URL}" ]; then
    MAVEN_BUILD_ARGS+=(
        "-Ddl4j.repository.id=${DL4J_MAVEN_REPOSITORY_ID}"
        "-Ddl4j.repository.url=${DL4J_MAVEN_REPOSITORY_URL}"
    )
fi
if [ -n "${ND4J_BACKEND}" ]; then
    MAVEN_BUILD_ARGS+=(
        "-Dnd4j.backend=${ND4J_BACKEND}"
        "-Dkompile.backend=${KOMPILE_BACKEND_PROFILE}"
        "-Djavacpp.platform=${PLATFORM}"
    )
fi
if [ -n "${CUDA_FLAG}" ]; then
    MAVEN_BUILD_ARGS+=("${CUDA_FLAG}")
fi
if [ -n "${EXTRA_MVN_FLAGS}" ]; then
    MAVEN_BUILD_ARGS+=("${EXTRA_MVN_FLAGS}")
fi
# Large Windows PE/COFF images hit a GraalVM relocation-table layout bug at -O2.
# Activate the root POM's targeted -O1 profile only for release distribution builds.
if [[ "${PLATFORM}" == windows-* ]]; then
    MAVEN_BUILD_ARGS+=("-Dkompile.windows.pe-safe=true")
fi

# Mixed-vintage guard: kompile bundles dl4j straight from ~/.m2, which accumulates partial `-pl`
# installs across sessions (api/presets/natives from different builds). Mixed vintages produce
# native-contract crashes that masquerade as dl4j bugs (2026-07-05 CUDA-700 misdiagnosis: Jun-22
# cuda preset bundled under Jul-5 natives). Warn when the SNAPSHOT set spans >6h of build times.
if [ -n "${ND4J_BACKEND}" ]; then
    DL4J_M2="${MAVEN_REPOSITORY_SHELL}/org/eclipse/deeplearning4j"
    if [ -d "${DL4J_M2}" ]; then
        VINTAGE_SPAN_H=$(find "${DL4J_M2}" -maxdepth 3 -name '*-1.0.0-SNAPSHOT*.jar' -printf '%T@\n' 2>/dev/null | sort -n | awk 'NR==1{f=$1} {l=$1} END{if (NR>1) printf "%d", (l-f)/3600}' || true)
        if [ -n "${VINTAGE_SPAN_H:-}" ] && [ "${VINTAGE_SPAN_H}" -gt 6 ]; then
            echo "⚠️  WARNING: dl4j SNAPSHOT jars in ~/.m2 span ${VINTAGE_SPAN_H}h of build vintages."
            echo "    Mixed api/preset/native vintages cause illegal-memory-access crashes at runtime."
            echo "    Rebuild the nd4j reactor at HEAD in ONE pass before trusting this distribution."
        fi
    fi
fi

# Optimized-flavor guard: -Dlibnd4j.extension=<ext> activates the matching profile in nd4j-native's
# pom, which sets javacpp.platform.extension=-<ext> and moves EVERY native artifact onto a suffixed
# classifier (linux-x86_64-avx2) — including the libnd4j zip the backend unpacks. Those exist only
# where libnd4j itself was built with the extension. Without them Maven dies on a bare "Could not
# find artifact" partway into a native build that has already burned half an hour, so check up front.
#
# This is deliberately a hard stop and not a fallback to the baseline classifier: silently dropping
# the flag would ship a generic x86_64 build under an "${VARIANT}"-flavored name, and a per-platform
# flavor has to be a real build of that flavor.
LIBND4J_EXTENSION=""
if [ "${ND4J_BACKEND}" = "nd4j-native" ] && [[ "${KOMPILE_BACKEND_PROFILE}" == cpu-* ]]; then
    LIBND4J_EXTENSION="${KOMPILE_BACKEND_PROFILE#cpu-}"
fi
MAVEN_WILL_RUN=true
if [ "${SKIP_JAVA_BUILD}" = true ] && [ "${SKIP_NATIVE}" = true ] && [ "${JARS_ONLY}" = false ]; then
    MAVEN_WILL_RUN=false   # assembly-only: nothing resolves dependencies, so nothing can fail on them
fi
if [ -n "${LIBND4J_EXTENSION}" ] && [ -n "${ND4J_BACKEND}" ] && [ "${MAVEN_WILL_RUN}" = true ] \
        && [ -z "${DL4J_MAVEN_REPOSITORY_URL}" ]; then
    BACKEND_M2="${MAVEN_REPOSITORY_SHELL}/org/eclipse/deeplearning4j/${ND4J_BACKEND}"
    FLAVOR_JAR="$(find "${BACKEND_M2}" -name "*-${PLATFORM}-${LIBND4J_EXTENSION}.jar" -print -quit 2>/dev/null || true)"
    if [ -z "${FLAVOR_JAR}" ]; then
        echo "ERROR: variant '${VARIANT}' builds the ${LIBND4J_EXTENSION} flavor, but ${MAVEN_REPOSITORY_SHELL} has no" >&2
        echo "       ${ND4J_BACKEND} jar with the ${PLATFORM}-${LIBND4J_EXTENSION} classifier." >&2
        echo "" >&2
        echo "       Produce it from the dl4j checkout — libnd4j has to be compiled with the" >&2
        echo "       extension before nd4j-native can package it:" >&2
        echo "         mvn install -pl :libnd4j,:${ND4J_BACKEND} -am -DskipTests \\" >&2
        echo "             -Dlibnd4j.extension=${LIBND4J_EXTENSION} -Dlibnd4j.chip=cpu" >&2
        echo "" >&2
        echo "       Present classifiers:" >&2
        find "${BACKEND_M2}" -name '*.jar' -printf '         %f\n' 2>/dev/null | sort -u >&2 || true
        exit 1
    fi
fi

# ── Step 1: Java build ───────────────────────────────────────────────────────

if [ "${SKIP_JAVA_BUILD}" = false ]; then
    echo "──── Step 1: Building Java modules ────────────────────────────────"
    echo ""

    BUILD_CMD=("${MVN}" clean install -DskipTests "${MAVEN_BUILD_ARGS[@]}")
    JAVA_BUILD_MODULES=""
    JAVA_BUILD_ALSO_MAKE=false
    case "${VARIANT}" in
        cli-only)
            # A plain CLI-only archive does not require DL4J. Once a backend profile is
            # selected, however, every request-scoped runtime copied below must be part
            # of this clean reactor build so stale target/ output cannot enter the dist.
            if [ "${JARS_ONLY}" = true ]; then
                JAVA_BUILD_MODULES=":kompile-cli-main,:kompile-app-cli,:kompile-model-cli,:kompile-agent-cli,:kompile-component-cli"
            else
                JAVA_BUILD_MODULES=":kompile-cli-main,:kompile-model-cli,:kompile-agent-cli"
            fi
            if [ "${LOCAL_RUNTIME}" = true ]; then
                JAVA_BUILD_MODULES+=",:kompile-app-subprocess-serving,:kompile-pipeline-serving"
            fi
            JAVA_BUILD_ALSO_MAKE=true
            ;;
        local)
            # Keep the Java reactor at the local execution boundary. app-main is
            # included only because it currently owns the dedicated VLM entrypoint;
            # the combined staging REST/CLI application is intentionally excluded.
            # Upstream Kompile/DL4J artifacts must already be installed; do not widen
            # this focused build with Maven's also-make reactor expansion.
            JAVA_BUILD_MODULES=":kompile-cli-main,:kompile-model-cli,:kompile-agent-cli,:kompile-app-subprocess-serving,:kompile-pipeline-serving,:kompile-app-main"
            if [ "${JARS_ONLY}" = true ]; then
                JAVA_BUILD_MODULES+=",:kompile-app-cli,:kompile-component-cli"
            fi
            ;;
        full|hosted|cpu-intel|cpu-arm|cuda|amd-zluda)
            # Product distributions build only the leaf artifacts they package. Maven
            # supplies their dependency closure; unrelated root siblings such as
            # kompile-chat-local and kompile-e2e-tests must never enter this reactor.
            JAVA_BUILD_MODULES=":kompile-cli-main,:kompile-agent-cli,:kompile-app-cli,:kompile-model-cli,:kompile-component-cli,:kompile-app-main,:kompile-app-chat,:kompile-app-crawl-manager,:kompile-model-staging,:kompile-app-subprocess-serving,:kompile-pipeline-serving,:kompile-compute-graph-scripting,:kompile-app-lite,:kompile-sdk-serving"
            JAVA_BUILD_ALSO_MAKE=true
            ;;
    esac
    if [ -z "${JAVA_BUILD_MODULES}" ]; then
        echo "ERROR: no Java reactor boundary is defined for distribution variant '${VARIANT}'" >&2
        exit 1
    fi
    BUILD_CMD+=(-pl "${JAVA_BUILD_MODULES}")
    if [ "${JAVA_BUILD_ALSO_MAKE}" = true ]; then
        BUILD_CMD+=(-am)
    fi
    # When building JARs (not native), produce exec JARs for app-main
    if { [ "${JARS_ONLY}" = true ] || [ "${SERVER_JARS_ONLY}" = true ]; } \
            && [ "${APP_NATIVE}" = true ]; then
        BUILD_CMD+=("-Dkompile.uber")
    fi

    printf '  Command:'
    printf ' %q' "${BUILD_CMD[@]}"
    printf '\n'
    echo ""
    "${BUILD_CMD[@]}" 2>&1 | tee /tmp/kompile-java-build.log
    echo ""
    echo "  ✓ Java build complete"
else
    echo "──── Step 1: Skipped (--skip-java-build) ──────────────────────────"
fi

# ── Step 1b: Build exec JARs if needed (skip-java-build + service jar tier) ──

# A target/ JAR may survive from another backend lane when --skip-java-build is
# used, and packaging must also defend against any incomplete focused reactor.
# Shaded JARs retain Maven metadata while Spring Boot JARs retain the dependency
# under BOOT-INF/lib, so accept either representation of the exact selected lane.
exec_jar_matches_backend() {
    local jar="$1"
    if [ -z "${ND4J_BACKEND}" ]; then
        return 0
    fi
    [ -n "${jar}" ] && [ -f "${jar}" ] || return 1

    # ROCm-qualified ZLUDA releases share one artifactId. Require the exact
    # native classifier as well as the Java backend so stale 7.2.4 target/
    # output cannot be relabelled as a 10.0.0 distribution (or vice versa).
    case "${KOMPILE_BACKEND_PROFILE}" in
        zluda-rocm-*)
            {
                unzip -p "${jar}" \
                    "BOOT-INF/lib/${ND4J_BACKEND}-${ND4J_VERSION}-${SDK_CLASSIFIER}.jar" \
                    >/dev/null 2>&1 \
                || unzip -p "${jar}" \
                    "org/nd4j/linalg/jcublas/bindings/${SDK_CLASSIFIER}/shared-runtime-manifest.txt" \
                    >/dev/null 2>&1
            } || return 1
            ;;
    esac

    {
        unzip -p "${jar}" "BOOT-INF/lib/${ND4J_BACKEND}-${ND4J_VERSION}.jar" \
            >/dev/null 2>&1 \
        || unzip -p "${jar}" \
            "META-INF/maven/org.eclipse.deeplearning4j/${ND4J_BACKEND}/pom.properties" \
            >/dev/null 2>&1
    }
}

if [ "${SKIP_JAVA_BUILD}" = true ] \
        && { [ "${JARS_ONLY}" = true ] || [ "${SERVER_JARS_ONLY}" = true ]; } \
        && [ "${APP_NATIVE}" = true ]; then
    # Check if exec JARs already exist
    APP_EXEC_JAR=$(ls kompile-app/kompile-app-parent/kompile-app-main/target/*-exec.jar 2>/dev/null | head -1)
    CHAT_EXEC_JAR=$(ls kompile-app/kompile-app-parent/kompile-app-chat/target/*-exec.jar 2>/dev/null | head -1)
    CRAWL_MGR_EXEC_JAR=$(ls kompile-app/kompile-app-parent/kompile-app-crawl-manager/target/*-exec.jar 2>/dev/null | head -1)

    # --skip-java-build may reuse target/ from a previous backend lane. Presence
    # alone is not enough: a CUDA archive assembled after a CPU package run would
    # otherwise silently publish CPU-only service JARs. Require the lane's exact
    # backend artifact and rebuild only mismatched/missing exec JARs.
    invalidate_exec_jar_if_backend_mismatch() {
        local variable_name="$1"
        local label="$2"
        local jar="${!variable_name}"
        if [ -n "${jar}" ] && ! exec_jar_matches_backend "${jar}"; then
            echo "  ${label}: existing exec JAR does not contain ${ND4J_BACKEND}; rebuilding"
            printf -v "${variable_name}" '%s' ""
        fi
    }

    invalidate_exec_jar_if_backend_mismatch APP_EXEC_JAR kompile-app-main
    invalidate_exec_jar_if_backend_mismatch CHAT_EXEC_JAR kompile-app-chat
    invalidate_exec_jar_if_backend_mismatch CRAWL_MGR_EXEC_JAR kompile-app-crawl-manager

    if [ -z "${APP_EXEC_JAR}" ] || [ -z "${CHAT_EXEC_JAR}" ] \
       || [ -z "${CRAWL_MGR_EXEC_JAR}" ]; then
        echo ""
        echo "──── Step 1b: Building exec JARs ─────────────────────────────────"
        echo ""
    fi

    if [ -z "${APP_EXEC_JAR}" ]; then
        echo "  kompile-app-main: building exec JAR..."
        (
            cd kompile-app/kompile-app-parent/kompile-app-main
            "${MVN}" package -DskipTests -Dkompile.uber "${MAVEN_BUILD_ARGS[@]}" \
                2>&1 | tee /tmp/kompile-app-main-jar.log
        )
        echo "  ✓ kompile-app-main exec JAR built"
    fi

    # The persona apps emit their exec jar on every build (no -Dkompile.uber).
    # Rebuild when target/ is empty or when it belongs to another backend lane.
    for PERSONA_MODULE in kompile-app-chat kompile-app-crawl-manager; do
        case "${PERSONA_MODULE}" in
            kompile-app-chat) PERSONA_EXEC_JAR="${CHAT_EXEC_JAR}" ;;
            kompile-app-crawl-manager) PERSONA_EXEC_JAR="${CRAWL_MGR_EXEC_JAR}" ;;
        esac
        if [ -z "${PERSONA_EXEC_JAR}" ]; then
            echo "  ${PERSONA_MODULE}: building exec JAR..."
            (
                cd "kompile-app/kompile-app-parent/${PERSONA_MODULE}"
                "${MVN}" package -DskipTests "${MAVEN_BUILD_ARGS[@]}" \
                    2>&1 | tee "/tmp/${PERSONA_MODULE}-jar.log"
            )
            echo "  ✓ ${PERSONA_MODULE} exec JAR built"
        fi
    done
fi

# ── Step 2: Native image builds ──────────────────────────────────────────────

if [ "${SKIP_NATIVE}" = false ]; then
    echo ""
    echo "──── Step 2: Building native images ───────────────────────────────"
    echo ""

    NATIVE_BUILD_FLAG="-Dkompile.dist=true"
    PIDS=()
    FAILED=0

    # Keep at most PARALLEL native-image jobs in flight. Waiting FIFO is intentional: it is portable
    # across the Bash versions used by the supported build hosts and still provides a hard memory
    # bound. A reaped pid must leave PIDS, otherwise the final wait below reads a clean build as a
    # failure.
    throttle_native_build() {
        if [ ${#PIDS[@]} -ge "${PARALLEL}" ]; then
            local pid="${PIDS[0]}"
            if ! wait "${pid}"; then
                FAILED=$((FAILED + 1))
            fi
            PIDS=("${PIDS[@]:1}")
        fi
    }

    # CLI native (always)
    if [ "${CLI_NATIVE}" = true ]; then
        CLI_TARGET="kompile-cli/kompile-cli-main/target/kompile-cli-main${EXE_SUFFIX}"
        if [ -f "${CLI_TARGET}" ] && [ "${SKIP_JAVA_BUILD}" = true ]; then
            echo "  kompile-cli: using existing binary"
        else
            echo "  kompile-cli: building native image..."
            (
                cd kompile-cli/kompile-cli-main
                "${MVN}" package "${NATIVE_BUILD_FLAG}" -DskipTests "${MAVEN_BUILD_ARGS[@]}" \
                    2>&1 | tee /tmp/kompile-cli-native.log
            ) &
            PIDS+=($!)
            throttle_native_build
        fi
    fi

    # Standalone model CLI native. The main CLI delegates model operations to this
    # sibling process; a native parent must have the native child in the same distro.
    if [ "${CLI_NATIVE}" = true ]; then
        MODEL_CLI_TARGET="kompile-cli/kompile-model-cli/target/kompile-model${EXE_SUFFIX}"
        if [ -f "${MODEL_CLI_TARGET}" ] && [ "${SKIP_JAVA_BUILD}" = true ]; then
            echo "  kompile-model: using existing binary"
        else
            echo "  kompile-model: building native image..."
            (
                cd kompile-cli/kompile-model-cli
                "${MVN}" package "${NATIVE_BUILD_FLAG}" -DskipTests "${MAVEN_BUILD_ARGS[@]}" \
                    2>&1 | tee /tmp/kompile-model-native.log
            ) &
            PIDS+=($!)
            throttle_native_build
        fi
    fi

    # Every native CLI distribution carries kompile-agent: `kompile spin` and
    # `kompile agent` delegate to it even in cli-only/local variants. Product
    # distributions additionally carry the app/component CLIs.
    if [ "${CLI_NATIVE}" = true ]; then
        DELEGATED_CLIS=("kompile-cli/kompile-agent-cli:kompile-agent")
        if [ "${INCLUDE_PRODUCT_EXTRAS}" = true ]; then
            DELEGATED_CLIS+=(
                "kompile-cli/kompile-app-cli:kompile-app-cli"
                "kompile-cli/kompile-component-cli:kompile-component"
            )
        fi
        for DELEGATED_CLI in "${DELEGATED_CLIS[@]}"; do
            DELEGATED_CLI_MODULE="${DELEGATED_CLI%%:*}"
            DELEGATED_CLI_IMAGE="${DELEGATED_CLI##*:}"
            DELEGATED_CLI_TARGET="${DELEGATED_CLI_MODULE}/target/${DELEGATED_CLI_IMAGE}${EXE_SUFFIX}"
            if [ -f "${DELEGATED_CLI_TARGET}" ] && [ "${SKIP_JAVA_BUILD}" = true ]; then
                echo "  ${DELEGATED_CLI_IMAGE}: using existing binary"
            else
                echo "  ${DELEGATED_CLI_IMAGE}: building native image..."
                (
                    cd "${DELEGATED_CLI_MODULE}"
                    "${MVN}" package "${NATIVE_BUILD_FLAG}" -DskipTests "${MAVEN_BUILD_ARGS[@]}" \
                        2>&1 | tee "/tmp/${DELEGATED_CLI_IMAGE}-native.log"
                ) &
                PIDS+=($!)
                throttle_native_build
            fi
        done
    fi

    # App main native
    if [ "${APP_NATIVE}" = true ] && [ "${SERVER_JARS_ONLY}" = false ]; then
        echo "  kompile-app-main: building native image..."
        (
            cd kompile-app/kompile-app-parent/kompile-app-main
            # -Dkompile.uber also produces the exec jar alongside the native build
            "${MVN}" package "${NATIVE_BUILD_FLAG}" -Dkompile.uber -DskipTests "${MAVEN_BUILD_ARGS[@]}" \
                2>&1 | tee /tmp/kompile-app-main-native.log
        ) &
        PIDS+=($!)
        throttle_native_build
    fi

    # Persona app natives (chat :8081, crawl-manager :8082).
    # Gated on APP_NATIVE with app-main: the three are one server story, and shipping an AOT
    # admin console beside interpreted persona apps is the drift this whole step exists to avoid.
    # No -Dkompile.uber needed: the personas repackage on every build, so this one command
    # leaves BOTH the binary and the exec jar in target/ — the same pairing the launchers
    # expect from app-main (native preferred, jar fallback).
    if [ "${APP_NATIVE}" = true ] && [ "${SERVER_JARS_ONLY}" = false ]; then
        for PERSONA_MODULE in kompile-app-chat kompile-app-crawl-manager; do
            echo "  ${PERSONA_MODULE}: building native image..."
            (
                cd "kompile-app/kompile-app-parent/${PERSONA_MODULE}"
                "${MVN}" package "${NATIVE_BUILD_FLAG}" -DskipTests "${MAVEN_BUILD_ARGS[@]}" \
                    2>&1 | tee "/tmp/${PERSONA_MODULE}-native.log"
            ) &
            PIDS+=($!)
            throttle_native_build
        done
    fi

    # Model staging native
    if [ "${STAGING_NATIVE}" = true ] && [ "${SERVER_JARS_ONLY}" = false ]; then
        echo "  kompile-model-staging: building native image..."
        (
            cd kompile-app/kompile-models/kompile-model-staging
            "${MVN}" package "${NATIVE_BUILD_FLAG}" -DskipTests "${MAVEN_BUILD_ARGS[@]}" \
                2>&1 | tee /tmp/kompile-model-staging-native.log
        ) &
        PIDS+=($!)
        throttle_native_build
    fi

    # Standalone request-scoped model and pipeline runtimes. These are direct
    # subprocess entrypoints; neither image dispatches through kompile-app-main.
    if [ "${LOCAL_RUNTIME}" = true ] && [ "${SERVER_JARS_ONLY}" = false ]; then
        for RUNTIME in \
            "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving:kompile-model-serving" \
            "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving:kompile-pipeline-serving"; do
            RUNTIME_MODULE="${RUNTIME%%:*}"
            RUNTIME_IMAGE="${RUNTIME##*:}"
            echo "  ${RUNTIME_IMAGE}: building native image..."
            (
                cd "${RUNTIME_MODULE}"
                "${MVN}" package "${NATIVE_BUILD_FLAG}" -DskipTests "${MAVEN_BUILD_ARGS[@]}" \
                    2>&1 | tee "/tmp/${RUNTIME_IMAGE}-native.log"
            ) &
            PIDS+=($!)
            throttle_native_build
        done
    fi

    # Wait for whatever is still in flight (empty in serial mode because every launch was reaped).
    # Do not expand an empty array under `set -u`: macOS Bash 3.2 treats it as unbound.
    echo ""
    echo "  Waiting for ${#PIDS[@]} native build(s)..."
    if [ "${#PIDS[@]}" -gt 0 ]; then
        for pid in "${PIDS[@]}"; do
            if ! wait "${pid}"; then
                FAILED=$((FAILED + 1))
            fi
        done
    fi

    if [ "${FAILED}" -gt 0 ]; then
        echo "  ✗ ${FAILED} native build(s) failed. Check /tmp/kompile-*-native.log"
        exit 1
    fi
    echo "  ✓ All native images built"
else
    echo ""
    echo "──── Step 2: Skipped (--skip-native) ──────────────────────────────"
fi

# ── Step 2b: Bundle jlink runtime (all variants except cli-only) ─────────────

build_runtime() {
    local dest="$1"

    # Locate a JDK (prefer Temurin/regular JDK over GraalVM for smaller runtime).
    local jdk_home=""
    if [ -x "${KOMPILE_JAVA:-}" ]; then
        # KOMPILE_JAVA may point to the java binary; walk up to find the JDK root.
        jdk_home="$(cd "$(dirname "${KOMPILE_JAVA}")/.." && pwd)"
    elif [ -d "${JAVA_HOME:-}/jmods" ]; then
        jdk_home="${JAVA_HOME}"
    elif [ -d "${GRAALVM_HOME:-}/jmods" ]; then
        jdk_home="${GRAALVM_HOME}"
    else
        # Try java on PATH
        local java_path
        java_path="$(command -v java 2>/dev/null || true)"
        if [ -n "${java_path}" ]; then
            local resolved
            resolved="$(readlink -f "${java_path}" 2>/dev/null || echo "${java_path}")"
            jdk_home="$(cd "$(dirname "${resolved}")/../.." && pwd)"
        fi
    fi

    if [ -z "${jdk_home}" ] || [ ! -d "${jdk_home}/jmods" ]; then
        echo "  WARN: no jmods directory found (tried JAVA_HOME=${JAVA_HOME:-}, GRAALVM_HOME=${GRAALVM_HOME:-}, PATH java)"
        echo "  WARN: runtime not bundled; jar tier will need a system JDK"
        return 0
    fi

    local jlink="${jdk_home}/bin/jlink"
    if [ ! -x "${jlink}" ]; then
        echo "  WARN: jlink not found at ${jlink} — runtime not bundled; jar tier will need a system JDK"
        return 0
    fi

    echo "  Bundling jlink runtime from ${jdk_home} ..."

    local modules="java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs,jdk.management,jdk.management.agent,jdk.security.auth,jdk.naming.dns,jdk.charsets,jdk.localedata,jdk.httpserver,jdk.jfr"
    local base_opts="--add-modules ${modules} --strip-debug --no-header-files --no-man-pages --output ${dest}"

    # JDK 17 exposes the legacy numeric compression levels, while newer
    # jlink releases use named ZIP levels. Select the syntax advertised by the
    # actual bundled JDK so a supported Java 17 build does not emit false errors.
    local jlink_help
    jlink_help="$("${jlink}" --help 2>&1 || true)"
    local compress_opt="--compress=zip-6"
    if [[ "${jlink_help}" == *"--compress=<0|1|2>"* ]]; then
        compress_opt="--compress=2"
    fi
    local locale_opt="--include-locales=en"

    local ok=false
    if "${jlink}" ${base_opts} ${compress_opt} ${locale_opt} 2>/tmp/kompile-jlink.log; then
        ok=true
    else
        rm -rf "${dest}"
        # Try without --include-locales (no jdk.jlink locale plugin on some JDKs).
        if "${jlink}" ${base_opts} ${compress_opt} 2>/tmp/kompile-jlink.log; then
            ok=true
        else
            rm -rf "${dest}"
            # Fall back to --compress=2 (jlink <JDK 18 syntax).
            if "${jlink}" ${base_opts} --compress=2 2>/tmp/kompile-jlink.log; then
                ok=true
            else
                rm -rf "${dest}" 2>/dev/null || true
            fi
        fi
    fi

    if [ "${ok}" = false ]; then
        echo "  WARN: jlink failed (see /tmp/kompile-jlink.log); runtime not bundled"
        cat /tmp/kompile-jlink.log >&2 || true
        return 0
    fi

    # Verify the bundled runtime works.
    if "${dest}/bin/java" -version 2>/dev/null; then
        local rt_ver
        rt_ver=$("${dest}/bin/java" -version 2>&1 | head -1)
        echo "  runtime/bin/java: ${rt_ver}"
    else
        echo "  WARN: bundled runtime failed -version check; removing"
        rm -rf "${dest}"
    fi
}

if [ "${BUNDLE_RUNTIME}" = true ]; then
    echo ""
    echo "──── Step 2b: Bundling jlink runtime ──────────────────────────────"
    echo ""
    RUNTIME_DEST="${OUTPUT_DIR}/.runtime-stage"
    rm -rf "${RUNTIME_DEST}"
    build_runtime "${RUNTIME_DEST}"
    if [ "${VARIANT}" = full ] && [ ! -x "${RUNTIME_DEST}/bin/java" ]; then
        echo "ERROR: the full variant requires a working bundled Java runtime." >&2
        exit 1
    fi
fi

# ── Binary portability normalizer ────────────────────────────────────────────
# Both distribution routes call this helper. It patches only staged copies and
# fails closed for Linux ELF files when patchelf is unavailable.
ELF_NORMALIZER="${SCRIPT_DIR}/kompile-dist/src/main/build/normalize-elf-portability.sh"
normalize_elf_portability() {
    bash "${ELF_NORMALIZER}" "$1"
}

# ── Step 3: Package distribution ─────────────────────────────────────────────

echo ""
echo "──── Step 3: Packaging distribution ───────────────────────────────"
echo ""

DIST_NAME="kompile-dist-${VERSION}-${DISTRIBUTION_CLASSIFIER}"
DIST_DIR="${OUTPUT_DIR}/${DIST_NAME}"

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"/{bin,lib,config,data}

# Copy jlink runtime into the dist (if it was staged above).
if [ "${BUNDLE_RUNTIME}" = true ] && [ -d "${RUNTIME_DEST:-}" ] && [ -x "${RUNTIME_DEST}/bin/java" ]; then
    cp -a "${RUNTIME_DEST}" "${DIST_DIR}/runtime"
    echo "  runtime/ (bundled JDK — $(du -sh "${DIST_DIR}/runtime" | cut -f1))"
fi

# Copy the native CLI when the native tier is selected (canonical name: bin/kompile;
# back-compat symlink: bin/kompile-cli).
CLI_BIN="kompile-cli/kompile-cli-main/target/kompile-cli-main${EXE_SUFFIX}"
if [ "${CLI_NATIVE}" = true ] && [ -f "${CLI_BIN}" ]; then
    cp "${CLI_BIN}" "${DIST_DIR}/bin/kompile${EXE_SUFFIX}"
    chmod +x "${DIST_DIR}/bin/kompile${EXE_SUFFIX}"
    normalize_elf_portability "${DIST_DIR}/bin/kompile${EXE_SUFFIX}"
    if [ -n "${EXE_SUFFIX}" ]; then
        cp "${DIST_DIR}/bin/kompile${EXE_SUFFIX}" "${DIST_DIR}/bin/kompile-cli${EXE_SUFFIX}"
        echo "  bin/kompile${EXE_SUFFIX} ($(du -h "${CLI_BIN}" | cut -f1)) + compatibility copy"
    else
        ln -sf kompile "${DIST_DIR}/bin/kompile-cli"
        echo "  bin/kompile ($(du -h "${CLI_BIN}" | cut -f1)) + bin/kompile-cli symlink"
    fi
fi
if [ "${CLI_NATIVE}" = true ] && [ ! -x "${DIST_DIR}/bin/kompile${EXE_SUFFIX}" ]; then
    echo "  ERROR: required CLI native binary is missing: ${CLI_BIN}" >&2
    exit 1
fi

# The model CLI is a sibling native image, not a classpath entry. Native
# distributions must ship it beside the main CLI for model commands and MCP conversion
# to stay on the native child-process ABI.
MODEL_CLI_BIN="kompile-cli/kompile-model-cli/target/kompile-model${EXE_SUFFIX}"
if [ "${CLI_NATIVE}" = true ] && [ -f "${MODEL_CLI_BIN}" ]; then
    cp "${MODEL_CLI_BIN}" "${DIST_DIR}/bin/kompile-model${EXE_SUFFIX}"
    chmod +x "${DIST_DIR}/bin/kompile-model${EXE_SUFFIX}"
    normalize_elf_portability "${DIST_DIR}/bin/kompile-model${EXE_SUFFIX}"
    echo "  bin/kompile-model${EXE_SUFFIX} ($(du -h "${MODEL_CLI_BIN}" | cut -f1))"
fi
if [ "${CLI_NATIVE}" = true ] && [ ! -x "${DIST_DIR}/bin/kompile-model${EXE_SUFFIX}" ]; then
    echo "  ERROR: required model CLI native binary is missing: ${MODEL_CLI_BIN}" >&2
    exit 1
fi

# Copy the shaded CLI uber JAR into lib/ for the JVM/JBang tier (and for the
# existing native distributions that expose a JVM fallback).
if [ "${INCLUDE_CLI_JAR}" = true ]; then
    CLI_SHADED_JAR="kompile-cli/kompile-cli-main/target/kompile-cli-main-${VERSION}-shaded.jar"
    if [ -f "${CLI_SHADED_JAR}" ]; then
        cp "${CLI_SHADED_JAR}" "${DIST_DIR}/lib/kompile-cli.jar"
        echo "  lib/kompile-cli.jar ($(du -h "${CLI_SHADED_JAR}" | cut -f1))"
    else
        CLI_SHADED_GLOB=$(find kompile-cli/kompile-cli-main/target -maxdepth 1 \
            -name '*-shaded.jar' -print -quit 2>/dev/null || true)
        if [ -n "${CLI_SHADED_GLOB}" ]; then
            cp "${CLI_SHADED_GLOB}" "${DIST_DIR}/lib/kompile-cli.jar"
            echo "  lib/kompile-cli.jar ($(du -h "${CLI_SHADED_GLOB}" | cut -f1)) [fallback: project version]"
        else
            echo "  WARN: ${CLI_SHADED_JAR} not found — lib/kompile-cli.jar will be absent (build with shade plugin to include)"
        fi
    fi
fi
if [ "${CLI_NATIVE}" = false ] && [ ! -f "${DIST_DIR}/lib/kompile-cli.jar" ]; then
    echo "  ERROR: JVM distribution requires lib/kompile-cli.jar" >&2
    exit 1
fi
if [ "${VARIANT}" = full ] && [ ! -f "${DIST_DIR}/lib/kompile-cli.jar" ]; then
    echo "  ERROR: full distribution requires lib/kompile-cli.jar" >&2
    exit 1
fi

# The JBang wrapper is also installed as bin/kompile so a jars-only archive
# keeps the canonical command name without shadowing native bin/kompile.
if [ "${JARS_ONLY}" = true ]; then
    CLI_WRAPPER_SRC="kompile-dist/src/main/scripts/kompile.sh"
    if [ ! -f "${CLI_WRAPPER_SRC}" ]; then
        echo "  ERROR: missing JBang CLI wrapper: ${CLI_WRAPPER_SRC}" >&2
        exit 1
    fi
    cp "${CLI_WRAPPER_SRC}" "${DIST_DIR}/bin/kompile.sh"
    cp "${CLI_WRAPPER_SRC}" "${DIST_DIR}/bin/kompile"
    chmod +x "${DIST_DIR}/bin/kompile.sh" "${DIST_DIR}/bin/kompile"
    echo "  bin/kompile + bin/kompile.sh (JBang/JVM wrapper)"
fi

# Copy app-main (native binary: bin/kompile-server; back-compat symlink: bin/kompile-app-main)
if [ "${APP_NATIVE}" = true ]; then
    APP_BIN="kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app"
    APP_EXEC_JAR_PATH="kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app-main-${VERSION}-exec.jar"
    if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ] && [ -f "${APP_BIN}" ]; then
        cp "${APP_BIN}" "${DIST_DIR}/bin/kompile-server"
        chmod +x "${DIST_DIR}/bin/kompile-server"
        normalize_elf_portability "${DIST_DIR}/bin/kompile-server"
        # Back-compat symlink
        ln -sf kompile-server "${DIST_DIR}/bin/kompile-app-main"
        echo "  bin/kompile-server ($(du -h "${APP_BIN}" | cut -f1)) + bin/kompile-app-main symlink"
        # GraalVM-emitted JDK shim libraries are side-loaded with every other native
        # dependency. The canonical $ORIGIN/../lib RPATH also works for direct MCP launches.
        SHIM_COUNT=0
        for shim in kompile-app/kompile-app-parent/kompile-app-main/target/lib*.so; do
            [ -f "${shim}" ] || continue
            cp -an "${shim}" "${DIST_DIR}/lib/" 2>/dev/null || true
            SHIM_COUNT=$((SHIM_COUNT + 1))
        done
        if [ "${SHIM_COUNT}" -gt 0 ]; then
            echo "  lib/ (+${SHIM_COUNT} GraalVM JDK shim libraries)"
        fi
    fi
    # Copy exec jar into lib/ (present when -Dkompile.uber was passed, which native build does)
    if [ -f "${APP_EXEC_JAR_PATH}" ]; then
        cp "${APP_EXEC_JAR_PATH}" "${DIST_DIR}/lib/kompile-server.jar"
        echo "  lib/kompile-server.jar ($(du -h "${APP_EXEC_JAR_PATH}" | cut -f1))"
    else
        # Fallback: try glob in case version differs
        APP_EXEC_GLOB=$(ls kompile-app/kompile-app-parent/kompile-app-main/target/*-exec.jar 2>/dev/null | head -1)
        if [ -n "${APP_EXEC_GLOB}" ]; then
            cp "${APP_EXEC_GLOB}" "${DIST_DIR}/lib/kompile-server.jar"
            echo "  lib/kompile-server.jar ($(du -h "${APP_EXEC_GLOB}" | cut -f1)) [fallback: JAR]"
        else
            echo "  WARN: kompile-app-main exec jar not found — lib/kompile-server.jar will be absent (build with -Dkompile.uber)"
        fi
    fi

    # JavaScript/Python execution is deliberately outside the native-image closure. The server
    # carries only a lightweight NodeExecutor client and launches this exec JAR with the bundled
    # Java runtime. It is a required server runtime artifact, not an optional fallback.
    SCRIPTING_WORKER_TARGET="kompile-app/kompile-data/kompile-compute-graphs/kompile-compute-graph-scripting/target"
    SCRIPTING_WORKER_JAR="${SCRIPTING_WORKER_TARGET}/kompile-compute-graph-scripting-${VERSION}-exec.jar"
    if [ ! -f "${SCRIPTING_WORKER_JAR}" ]; then
        SCRIPTING_WORKER_JAR=$(ls "${SCRIPTING_WORKER_TARGET}"/*-exec.jar 2>/dev/null | head -1)
    fi
    if [ -n "${SCRIPTING_WORKER_JAR}" ] && [ -f "${SCRIPTING_WORKER_JAR}" ]; then
        cp "${SCRIPTING_WORKER_JAR}" "${DIST_DIR}/lib/kompile-scripting-worker.jar"
        echo "  lib/kompile-scripting-worker.jar ($(du -h "${SCRIPTING_WORKER_JAR}" | cut -f1))"
    else
        echo "  ERROR: scripting worker exec jar not found — build :kompile-compute-graph-scripting first" >&2
    fi
fi

# Copy model staging in the selected execution form. The JVM tier uses the
# existing Spring Boot executable JAR; the native tier keeps the side-loaded
# native payload behavior.
if [ "${STAGING_NATIVE}" = true ]; then
    STAGING_TARGET="kompile-app/kompile-models/kompile-model-staging/target"
    STAGING_SHIPPED=false

    if [ "${JARS_ONLY}" = true ] || [ "${SERVER_JARS_ONLY}" = true ]; then
        STAGING_EXEC_JAR=$(ls "${STAGING_TARGET}"/*-exec.jar 2>/dev/null | head -1 || true)
        if [ -n "${STAGING_EXEC_JAR}" ] && [ -f "${STAGING_EXEC_JAR}" ]; then
            cp "${STAGING_EXEC_JAR}" "${DIST_DIR}/lib/kompile-model-staging.jar"
            echo "  lib/kompile-model-staging.jar ($(du -h "${STAGING_EXEC_JAR}" | cut -f1))"
            STAGING_SHIPPED=true
        fi
    fi

    if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ] \
            && [ -f "${STAGING_TARGET}/kompile-model-staging" ]; then
        cp "${STAGING_TARGET}/kompile-model-staging" "${DIST_DIR}/bin/kompile-model-staging"
        chmod +x "${DIST_DIR}/bin/kompile-model-staging"
        normalize_elf_portability "${DIST_DIR}/bin/kompile-model-staging"
        echo "  bin/kompile-model-staging ($(du -h "${STAGING_TARGET}/kompile-model-staging" | cut -f1))"
        STAGING_SHIPPED=true
        # GraalVM JDK shim libraries share the canonical side-loaded lib/ directory.
        STAGING_SHIMS=0
        for shim in "${STAGING_TARGET}"/lib*.so; do
            [ -f "${shim}" ] || continue
            cp -an "${shim}" "${DIST_DIR}/lib/" 2>/dev/null || true
            STAGING_SHIMS=$((STAGING_SHIMS + 1))
        done
        if [ "${STAGING_SHIMS}" -gt 0 ]; then
            echo "  lib/ (+${STAGING_SHIMS} GraalVM JDK shim libraries from kompile-model-staging)"
        fi
    fi

    if [ "${STAGING_SHIPPED}" = false ]; then
        echo "  ERROR: model-staging worker is missing in the selected form" >&2
        exit 1
    fi
fi

# Ship request-scoped local runtimes in the selected execution form.
if [ "${LOCAL_RUNTIME}" = true ]; then
    for RUNTIME in \
        "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving:kompile-model-serving" \
        "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving:kompile-pipeline-serving"; do
        RUNTIME_MODULE="${RUNTIME%%:*}"
        RUNTIME_ARTIFACT="${RUNTIME##*:}"
        RUNTIME_TARGET="${RUNTIME_MODULE}/target"
        RUNTIME_SHIPPED=false

        if [ "${JARS_ONLY}" = true ] || [ "${SERVER_JARS_ONLY}" = true ]; then
            RUNTIME_EXEC_JAR=$(ls "${RUNTIME_TARGET}"/*-exec.jar 2>/dev/null | head -1 || true)
            if [ -n "${RUNTIME_EXEC_JAR}" ] && [ -f "${RUNTIME_EXEC_JAR}" ]; then
                if ! exec_jar_matches_backend "${RUNTIME_EXEC_JAR}"; then
                    echo "  ERROR: ${RUNTIME_EXEC_JAR} does not contain the selected ${ND4J_BACKEND} backend" >&2
                    echo "  Refusing to package a stale or mismatched local runtime JAR." >&2
                    exit 1
                fi
                cp "${RUNTIME_EXEC_JAR}" "${DIST_DIR}/lib/${RUNTIME_ARTIFACT}.jar"
                echo "  lib/${RUNTIME_ARTIFACT}.jar ($(du -h "${RUNTIME_EXEC_JAR}" | cut -f1))"
                RUNTIME_SHIPPED=true
            fi
        fi

        if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ] \
                && [ -f "${RUNTIME_TARGET}/${RUNTIME_ARTIFACT}" ]; then
            cp "${RUNTIME_TARGET}/${RUNTIME_ARTIFACT}" "${DIST_DIR}/bin/${RUNTIME_ARTIFACT}"
            chmod +x "${DIST_DIR}/bin/${RUNTIME_ARTIFACT}"
            normalize_elf_portability "${DIST_DIR}/bin/${RUNTIME_ARTIFACT}"
            for shim in "${RUNTIME_TARGET}"/lib*.so; do
                [ -f "${shim}" ] || continue
                cp -an "${shim}" "${DIST_DIR}/lib/" 2>/dev/null || true
            done
            RUNTIME_SHIPPED=true
        fi

        if [ "${RUNTIME_SHIPPED}" = false ]; then
            echo "  ERROR: ${RUNTIME_ARTIFACT} worker is missing in the selected form" >&2
            exit 1
        fi
    done
fi

# Copy the end-user persona apps: chat (:8081) and the crawl manager (:8082).
#
# These ship whenever the server ships. kompile-app-main is the admin console now and no
# longer mounts the chat or crawl APIs at all, so a dist carrying only kompile-server has no
# chat and no crawl manager in it. See docs/architecture/app-persona-boundary.md.
#
# Both forms ship, exactly like app-main: the AOT binary in bin/ AND the exec jar in lib/.
# The launchers prefer the binary and fall back to the jar, so a dist that carried only one
# of them would silently lose either the fast start or the fallback. The binary comes from
# each persona's `native` profile (-Dkompile.dist); the exec jar is produced on every build
# — unlike app-main, a library whose exec jar is opt-in behind -Dkompile.uber.
#
# The native profile sets imageName to the DIST name (kompile-chat, kompile-crawl-manager),
# not the artifactId: the assembly descriptor dist.xml picks the binary up with a fileSet,
# which cannot rename, so the two dist builders only agree if the built name is already the
# shipped name.
#
# APP_NATIVE gates this because it means "the server ships in this variant" — it is true for
# every variant except cli-only, and --jars-only leaves the binary unbuilt so only the jar
# branch produces anything.
if [ "${APP_NATIVE}" = true ]; then
    for PERSONA in "kompile-app-chat:kompile-chat" "kompile-app-crawl-manager:kompile-crawl-manager"; do
        PERSONA_MODULE="${PERSONA%%:*}"
        PERSONA_ARTIFACT="${PERSONA##*:}"
        PERSONA_TARGET="kompile-app/kompile-app-parent/${PERSONA_MODULE}/target"
        PERSONA_SHIPPED=false

        if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ] \
                && [ -f "${PERSONA_TARGET}/${PERSONA_ARTIFACT}" ]; then
            cp "${PERSONA_TARGET}/${PERSONA_ARTIFACT}" "${DIST_DIR}/bin/${PERSONA_ARTIFACT}"
            chmod +x "${DIST_DIR}/bin/${PERSONA_ARTIFACT}"
            normalize_elf_portability "${DIST_DIR}/bin/${PERSONA_ARTIFACT}"
            echo "  bin/${PERSONA_ARTIFACT} ($(du -h "${PERSONA_TARGET}/${PERSONA_ARTIFACT}" | cut -f1))"
            PERSONA_SHIPPED=true
            # GraalVM JDK shims share lib/ with JavaCPP, ND4J, tokenizer, and CUDA
            # libraries so direct binaries and launcher scripts resolve one filesystem tree.
            PERSONA_SHIMS=0
            for shim in "${PERSONA_TARGET}"/lib*.so; do
                [ -f "${shim}" ] || continue
                cp -an "${shim}" "${DIST_DIR}/lib/" 2>/dev/null || true
                PERSONA_SHIMS=$((PERSONA_SHIMS + 1))
            done
            if [ "${PERSONA_SHIMS}" -gt 0 ]; then
                echo "  lib/ (+${PERSONA_SHIMS} GraalVM JDK shim libraries from ${PERSONA_MODULE})"
            fi
        fi

        if ls "${PERSONA_TARGET}"/*-exec.jar 1>/dev/null 2>&1; then
            PERSONA_JAR=$(ls "${PERSONA_TARGET}"/*-exec.jar | head -1)
            cp "${PERSONA_JAR}" "${DIST_DIR}/lib/${PERSONA_ARTIFACT}.jar"
            echo "  lib/${PERSONA_ARTIFACT}.jar ($(du -h "${PERSONA_JAR}" | cut -f1))"
            PERSONA_SHIPPED=true
        fi

        if [ "${PERSONA_SHIPPED}" = false ]; then
            echo "  WARN: no ${PERSONA_MODULE} binary or exec jar found — ${PERSONA_ARTIFACT} absent from this dist"
        fi
    done
fi

# A normal server distribution is a dual-form contract: the launcher must have an AOT binary and
# an exec-jar fallback. `--jars-only` deliberately relaxes only the native half. Fail before archive
# creation instead of publishing a manifest that merely describes a partial component.
require_component_forms() {
    local label="$1"
    local binary_name="$2"
    local jar_name="$3"
    local missing=0

    if [ ! -f "${DIST_DIR}/lib/${jar_name}" ]; then
        echo "  ERROR: ${label} exec jar is missing: lib/${jar_name}" >&2
        missing=1
    fi
    if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ] \
            && [ ! -x "${DIST_DIR}/bin/${binary_name}" ]; then
        echo "  ERROR: ${label} native binary is missing: bin/${binary_name}" >&2
        missing=1
    fi
    if [ "${missing}" -ne 0 ]; then
        echo "  Refusing to package an incomplete ${label} component." >&2
        exit 1
    fi
}

require_native_component() {
    local label="$1"
    local binary_name="$2"
    if [ ! -x "${DIST_DIR}/bin/${binary_name}" ]; then
        echo "  ERROR: ${label} native binary is missing: bin/${binary_name}" >&2
        echo "  Refusing to package an incomplete native MCP worker." >&2
        exit 1
    fi
}

if [ "${CLI_NATIVE}" = true ]; then
    require_native_component "model CLI" "kompile-model${EXE_SUFFIX}"
fi
if [ "${APP_NATIVE}" = true ]; then
    require_component_forms "server" "kompile-server" "kompile-server.jar"
    require_component_forms "chat" "kompile-chat" "kompile-chat.jar"
    require_component_forms "crawl-manager" "kompile-crawl-manager" "kompile-crawl-manager.jar"
    if [ ! -f "${DIST_DIR}/lib/kompile-scripting-worker.jar" ]; then
        echo "  ERROR: server scripting worker is missing: lib/kompile-scripting-worker.jar" >&2
        echo "  Refusing to package a server that cannot execute JavaScript/Python workflows." >&2
        exit 1
    fi
fi
if [ "${STAGING_NATIVE}" = true ]; then
    if [ "${JARS_ONLY}" = true ] || [ "${SERVER_JARS_ONLY}" = true ]; then
        require_component_forms "model-staging" "kompile-model-staging" "kompile-model-staging.jar"
    else
        require_native_component "model-staging" "kompile-model-staging"
    fi
fi
if [ "${LOCAL_RUNTIME}" = true ]; then
    if [ "${JARS_ONLY}" = true ] || [ "${SERVER_JARS_ONLY}" = true ]; then
        require_component_forms "model-serving" "kompile-model-serving" "kompile-model-serving.jar"
        require_component_forms "pipeline-serving" "kompile-pipeline-serving" "kompile-pipeline-serving.jar"
    else
        require_native_component "model-serving" "kompile-model-serving"
        require_native_component "pipeline-serving" "kompile-pipeline-serving"
    fi
fi
# Copy launchers at the payload boundary. Product/server variants retain the
# complete launcher set. A staging-only distribution keeps its native convenience
# launcher; the local execution distribution has no staging launcher.
SCRIPTS_SRC="kompile-dist/src/main/scripts"
if [ -d "${SCRIPTS_SRC}" ]; then
    if [ "${INCLUDE_PRODUCT_EXTRAS}" = true ]; then
        cp "${SCRIPTS_SRC}"/*.sh "${DIST_DIR}/bin/"
        chmod +x "${DIST_DIR}/bin/"*.sh
    elif [ "${STAGING_NATIVE}" = true ]; then
        cp "${SCRIPTS_SRC}/kompile-model-staging.sh" "${DIST_DIR}/bin/"
        chmod +x "${DIST_DIR}/bin/kompile-model-staging.sh"
    fi
    LAUNCHER_COUNT=$(ls "${DIST_DIR}/bin/"*.sh 2>/dev/null | wc -l || true)
    if [ "${LAUNCHER_COUNT}" -gt 0 ]; then
        echo "  bin/ (${LAUNCHER_COUNT} launcher scripts)"
    fi
fi

# JBang belongs to the JVM fallback tier. A jars-only CLI archive includes
# the catalog/docs even when product extras are otherwise disabled.
if [ "${INCLUDE_PRODUCT_EXTRAS}" = true ] || [ "${JARS_ONLY}" = true ]; then
    JBANG_CATALOG_SRC="kompile-dist/src/main/resources/jbang-catalog.json"
    JBANG_MD_SRC="kompile-dist/src/main/resources/JBANG.md"
    if [ -f "${JBANG_CATALOG_SRC}" ]; then
        cp "${JBANG_CATALOG_SRC}" "${DIST_DIR}/jbang-catalog.json"
        echo "  jbang-catalog.json"
    fi
    if [ -f "${JBANG_MD_SRC}" ]; then
        cp "${JBANG_MD_SRC}" "${DIST_DIR}/JBANG.md"
        echo "  JBANG.md"
    fi
fi

# ── Optional artifacts that dist.xml also lists (copy-if-present, logged when skipped) ──
# These are only produced when their respective sub-projects are built; all copies are
# safe to skip in cli-only or hosted builds that don't include those modules.
if [ "${INCLUDE_PRODUCT_EXTRAS}" = true ]; then

# kompile-sdk-serving shaded jar → lib/kompile-sdk-serving.jar
SDK_SERVING_JAR=$(find kompile-app/kompile-middleware/kompile-sdk-serving/target \
    -maxdepth 1 -name '*-shaded.jar' 2>/dev/null | head -1 || true)
if [ -n "${SDK_SERVING_JAR}" ]; then
    cp "${SDK_SERVING_JAR}" "${DIST_DIR}/lib/kompile-sdk-serving.jar"
    echo "  lib/kompile-sdk-serving.jar ($(du -h "${SDK_SERVING_JAR}" | cut -f1))"
else
    echo "  SKIP: kompile-sdk-serving shaded jar not found (build kompile-middleware to include)"
fi

# GraalVM shared library + headers from kompile-pipelines-framework-runtime → lib/
if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ]; then
    PFW_DIR="kompile-app/kompile-data/kompile-pipelines-framework/kompile-pipelines-framework-runtime/target"
    for pfw_file in libkompile_pipelines.so libkompile_pipelines.dylib libkompile_pipelines.dll \
                    graal_isolate.h graal_isolate_dynamic.h libkompile_pipelines.h libkompile_pipelines_dynamic.h; do
        if [ -f "${PFW_DIR}/${pfw_file}" ]; then
            cp "${PFW_DIR}/${pfw_file}" "${DIST_DIR}/lib/${pfw_file}"
            echo "  lib/${pfw_file} ($(du -h "${PFW_DIR}/${pfw_file}" | cut -f1))"
        else
            echo "  SKIP: ${pfw_file} not found at ${PFW_DIR} (build kompile-pipelines-framework-runtime with native profile)"
        fi
    done
fi

# CMake-built C wrapper → lib/libkompile_c_library.*
if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ]; then
    for clib_file in libkompile_c_library.so libkompile_c_library.dylib libkompile_c_library.dll; do
        if [ -f "kompile-c-library/${clib_file}" ]; then
            cp "kompile-c-library/${clib_file}" "${DIST_DIR}/lib/${clib_file}"
            echo "  lib/${clib_file} ($(du -h "kompile-c-library/${clib_file}" | cut -f1))"
        else
            echo "  SKIP: ${clib_file} not found (build kompile-c-library with CMake to include)"
        fi
    done
fi

# Python sdx_runtime wheel → python/*.whl
PYTHON_DIST="kompile-python/dist"
WHEEL_FOUND=false
if [ -d "${PYTHON_DIST}" ]; then
    for whl in "${PYTHON_DIST}"/*.whl; do
        if [ -f "${whl}" ]; then
            mkdir -p "${DIST_DIR}/python"
            cp "${whl}" "${DIST_DIR}/python/"
            echo "  python/$(basename "${whl}") ($(du -h "${whl}" | cut -f1))"
            WHEEL_FOUND=true
        fi
    done
fi
if [ "${WHEEL_FOUND}" = false ]; then
    echo "  SKIP: no Python wheel found at ${PYTHON_DIST}/ (build kompile-python to include)"
fi

# Default application configuration → conf/  (matches dist.xml conf/ fileSet)
CONF_SRC="kompile-app/kompile-app-parent/kompile-app-main/src/main/resources"
if [ -d "${CONF_SRC}" ]; then
    mkdir -p "${DIST_DIR}/conf"
    CONF_COUNT=0
    for conf_file in "${CONF_SRC}"/application*.properties \
                     "${CONF_SRC}"/application-*.properties \
                     "${CONF_SRC}"/log4j2*.xml \
                     "${CONF_SRC}"/log4j2.component.properties; do
        if [ -f "${conf_file}" ]; then
            cp "${conf_file}" "${DIST_DIR}/conf/"
            CONF_COUNT=$((CONF_COUNT + 1))
        fi
    done
    if [ "${CONF_COUNT}" -gt 0 ]; then
        echo "  conf/ (${CONF_COUNT} config files)"
    else
        echo "  SKIP: no application.properties/log4j2 files found under ${CONF_SRC}"
    fi
else
    echo "  SKIP: ${CONF_SRC} not found (kompile-app-main not checked out)"
fi

# Copy extra CLI binaries only for native distributions. The JVM tier uses the
# sibling shaded JARs copied below instead.
if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ]; then
    for extra in "kompile-cli/kompile-app-cli/target/kompile-app-cli${EXE_SUFFIX}" \
                 "kompile-cli/kompile-component-cli/target/kompile-component${EXE_SUFFIX}"; do
        if [ -f "${extra}" ]; then
            BNAME=$(basename "${extra}")
            cp "${extra}" "${DIST_DIR}/bin/${BNAME}"
            chmod +x "${DIST_DIR}/bin/${BNAME}"
            normalize_elf_portability "${DIST_DIR}/bin/${BNAME}"
            echo "  bin/${BNAME} ($(du -h "${extra}" | cut -f1))"
        fi
    done
fi
if [ "${CLI_NATIVE}" = true ]; then
    require_native_component "app CLI" "kompile-app-cli${EXE_SUFFIX}"
    require_native_component "component CLI" "kompile-component${EXE_SUFFIX}"
fi
fi

# The agent CLI is part of the CLI contract, not a product extra. Keep it in
# native cli-only/local archives as well as full product distributions.
if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ] \
        && [ "${CLI_NATIVE}" = true ]; then
    AGENT_CLI_BIN="kompile-cli/kompile-agent-cli/target/kompile-agent${EXE_SUFFIX}"
    if [ -f "${AGENT_CLI_BIN}" ]; then
        cp "${AGENT_CLI_BIN}" "${DIST_DIR}/bin/kompile-agent${EXE_SUFFIX}"
        chmod +x "${DIST_DIR}/bin/kompile-agent${EXE_SUFFIX}"
        normalize_elf_portability "${DIST_DIR}/bin/kompile-agent${EXE_SUFFIX}"
        echo "  bin/kompile-agent${EXE_SUFFIX} ($(du -h "${AGENT_CLI_BIN}" | cut -f1))"
    fi
    require_native_component "agent CLI" "kompile-agent${EXE_SUFFIX}"
fi

# A jars-only CLI carries the standalone delegated command JARs beside the
# main CLI uber JAR. MainCommand resolves these exact names through lib/.
if [ "${JARS_ONLY}" = true ]; then
    for CLI_CHILD in \
        "kompile-cli/kompile-agent-cli/target:kompile-agent.jar" \
        "kompile-cli/kompile-app-cli/target:kompile-app-cli.jar" \
        "kompile-cli/kompile-model-cli/target:kompile-model.jar" \
        "kompile-cli/kompile-component-cli/target:kompile-component.jar"; do
        CLI_CHILD_TARGET="${CLI_CHILD%%:*}"
        CLI_CHILD_NAME="${CLI_CHILD##*:}"
        CLI_CHILD_JAR=$(find "${CLI_CHILD_TARGET}" -maxdepth 1 -type f -name '*-shaded.jar' -print -quit 2>/dev/null || true)
        if [ -z "${CLI_CHILD_JAR}" ]; then
            CLI_CHILD_JAR=$(find "${CLI_CHILD_TARGET}" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit 2>/dev/null || true)
        fi
        if [ -z "${CLI_CHILD_JAR}" ]; then
            echo "  ERROR: required delegated CLI uber JAR is missing: ${CLI_CHILD_TARGET}/*.jar" >&2
            exit 1
        fi
        cp "${CLI_CHILD_JAR}" "${DIST_DIR}/lib/${CLI_CHILD_NAME}"
        echo "  lib/${CLI_CHILD_NAME} ($(du -h "${CLI_CHILD_JAR}" | cut -f1))"
    done

    # The full reactor may also have the optional self-contained Lite app.
    LITE_TARGET="kompile-app/kompile-app-parent/kompile-app-lite/target"
    LITE_JAR=$(find "${LITE_TARGET}" -maxdepth 1 -type f -name '*-exec.jar' -print -quit 2>/dev/null || true)
    if [ "${VARIANT}" != "cli-only" ] && [ -n "${LITE_JAR}" ]; then
        cp "${LITE_JAR}" "${DIST_DIR}/lib/kompile-lite.jar"
        echo "  lib/kompile-lite.jar ($(du -h "${LITE_JAR}" | cut -f1))"
    fi
fi

# Copy build scripts for platform rebuilds from installed dist
if [ -d "build-scripts" ]; then
    mkdir -p "${DIST_DIR}/build-scripts"
    cp build-scripts/*.sh "${DIST_DIR}/build-scripts/"
    chmod +x "${DIST_DIR}/build-scripts/"*.sh
    SCRIPT_COUNT=$(ls "${DIST_DIR}/build-scripts/"*.sh 2>/dev/null | wc -l)
    echo "  build-scripts/ (${SCRIPT_COUNT} scripts)"
fi

# Copy side-loaded native libraries for NativeLibraryResolver.  The Maven
# dependency unpack is intentionally broad because not every JNI dependency uses
# JavaCPP's platform directory convention.  The distribution boundary must be
# strict, though: flattening every platform into lib/ lets foreign libraries and
# stale CPU flavors overwrite the requested target by basename.
APP_NATIVE_LIBS="kompile-app/kompile-app-parent/kompile-app-main/target/native-libs"
CLI_NATIVE_LIBS="kompile-cli/kompile-cli-main/target/native-libs"

NATIVE_STAGER="${SCRIPT_DIR}/kompile-dist/src/main/build/stage-native-libs.sh"
# CUDA artifacts keep their producer manifest under the base platform classifier.
# Version-qualified ZLUDA artifacts instead own a ROCm-qualified classifier, so
# preserve that extension (and its manifest-declared .kpack resources) exactly.
case "${ND4J_BACKEND:-}" in
    nd4j-cuda-*|nd4j-zluda) NATIVE_PLATFORM_EXTENSION="" ;;
    *) NATIVE_PLATFORM_EXTENSION="${SDK_CLASSIFIER#${PLATFORM}}" ;;
esac

echo ""
# Never assemble from ~/.javacpp/cache: it is mutable user state and can contain
# stale classifiers with the same basename. Native service images require the
# exact side-loaded platform/flavor tree; jar-tier services load their libraries
# from their Maven dependencies and do not need this native-image-only staging.
if [ "${JARS_ONLY}" = false ] && [ "${SERVER_JARS_ONLY}" = false ]; then
    # The CLI has JNI dependencies even without a model backend (SQLite,
    # Conscrypt, Netty, and future runtime JARs). Stage that closure for every
    # native distribution instead of coupling JNI availability to ND4J.
    bash "${NATIVE_STAGER}" \
        "${CLI_NATIVE_LIBS}" \
        "${DIST_DIR}/lib" \
        "${PLATFORM}" \
        "${NATIVE_PLATFORM_EXTENSION}" \
        none

    if { [ "${APP_NATIVE}" = true ] || [ "${STAGING_NATIVE}" = true ] \
            || [ "${LOCAL_RUNTIME}" = true ]; }; then
        bash "${NATIVE_STAGER}" \
            "${APP_NATIVE_LIBS}" \
            "${DIST_DIR}/lib" \
            "${PLATFORM}" \
            "${NATIVE_PLATFORM_EXTENSION}" \
            "${ND4J_BACKEND:-none}"
    fi
else
    echo "  SKIP: side-loaded native libraries are not required by this variant"
fi
echo "  lib/ ($(du -sh "${DIST_DIR}/lib/" | cut -f1) including exec jars and target natives)"

# Backend distributions are a self-contained SDK boundary. The DL4J release
# publishes runtime packages separately from Maven, so repository-only builds
# must provide the matching SDK asset shard explicitly. Stage the shard, then
# retain only runtime packages whose declared lane matches the distribution
# backend. A CUDA build can emit a CPU-named compatibility package backed by the
# same CUDA library; shipping that as a CPU fallback is misleading and unsafe.
SDX_RUNTIME_COUNT=0
SDX_JAR_COUNT=0
if [ -n "${SDX_ASSETS_DIR}" ]; then
    if [ ! -d "${SDX_ASSETS_DIR}" ]; then
        echo "  ERROR: DL4J SDK assets directory does not exist: ${SDX_ASSETS_DIR}" >&2
        exit 1
    fi
    mkdir -p "${DIST_DIR}/sdx-sdk"
    cp -a "${SDX_ASSETS_DIR}/." "${DIST_DIR}/sdx-sdk/"
    case "${ND4J_BACKEND:-}" in
        nd4j-cuda-*|nd4j-zluda-*)
            rm -rf "${DIST_DIR}/sdx-sdk/cpu"
            rm -f "${DIST_DIR}/sdx-sdk/sdx-runtime-${PLATFORM}-cpu.zip" \
                "${DIST_DIR}/sdx-sdk/sdx-runtime-${PLATFORM}-cpu.zip.sha256" \
                "${DIST_DIR}/sdx-sdk/sdx-runtime-${PLATFORM}-cpu.zip.sha512"
            ;;
        nd4j-native)
            rm -rf "${DIST_DIR}/sdx-sdk/cuda"
            rm -f "${DIST_DIR}/sdx-sdk/sdx-runtime-${PLATFORM}-cuda.zip" \
                "${DIST_DIR}/sdx-sdk/sdx-runtime-${PLATFORM}-cuda.zip.sha256" \
                "${DIST_DIR}/sdx-sdk/sdx-runtime-${PLATFORM}-cuda.zip.sha512"
            ;;
    esac
    SDX_RUNTIME_COUNT=$(find "${DIST_DIR}/sdx-sdk" -type f \( -name '*.zip' -o -name '*.aar' \) | wc -l)
    SDX_JAR_COUNT=$(find "${DIST_DIR}/sdx-sdk/jars" -type f -name '*.jar' 2>/dev/null | wc -l || true)
    echo "  sdx-sdk/ (${SDX_RUNTIME_COUNT} runtime package(s), ${SDX_JAR_COUNT} platform JAR(s))"
fi
if [ -n "${ND4J_BACKEND}" ] && [ "${JARS_ONLY}" = false ]; then
    SDX_VALIDATOR="${SCRIPT_DIR}/kompile-dist/src/main/build/validate-sdx-assets.sh"
    if ! bash "${SDX_VALIDATOR}" "${DIST_DIR}/sdx-sdk" "${VARIANT}" "${PLATFORM}" \
            "${ND4J_VERSION}" "${CUDA_VERSION}" "${ND4J_BACKEND}" "${SDK_CLASSIFIER}"; then
        echo "  ERROR: ${VARIANT} requires the complete matching DL4J SDK lane." >&2
        echo "  Source builds collect it automatically; repository builds must pass --sdx-assets." >&2
        exit 1
    fi
fi

# Create seed data directories (matches GlobalBootstrap.ensureHomeDirectory)
mkdir -p "${DIST_DIR}/data"/{input_documents/uploads,shared_files}
mkdir -p "${DIST_DIR}/data"/{prompt-templates,models/.staging}
mkdir -p "${DIST_DIR}/data"/{logs,tool-definitions,folders}
mkdir -p "${DIST_DIR}/data"/{mcp-servers,mcp-bridges,pids}
mkdir -p "${DIST_DIR}"/{components,fact-sheets,archives}
mkdir -p "${DIST_DIR}/anserini/indexes"

# Seed files
echo '{"mcpServers":{}}' > "${DIST_DIR}/data/mcp-config.json"
echo "${VERSION}" > "${DIST_DIR}/.version"
echo "${VARIANT}" > "${DIST_DIR}/.variant"

# Report both shipped forms per component instead of one "is it here" boolean.
#
# Every server component ships twice — the AOT binary in bin/ and the exec jar in lib/ — and its
# launcher prefers the binary, falling back to the jar. A flat boolean cannot distinguish those,
# so a dist whose native build silently failed read as complete in the manifest.
component_forms() {
    binary_present=false
    jar_present=false
    if [ -f "${DIST_DIR}/bin/$1.exe" ]; then
        binary_present=true
    elif [ -f "${DIST_DIR}/bin/$1" ]; then
        # jars-only installs use a shell wrapper at bin/kompile. Do not report
        # that launcher as an AOT binary in the manifest.
        if [ "$1" != "kompile" ] || ! head -n 1 "${DIST_DIR}/bin/$1" | grep -q '^#!'; then
            binary_present=true
        fi
    fi
    [ -f "${DIST_DIR}/lib/$2" ] && jar_present=true
    present=false
    if [ "${binary_present}" = true ] || [ "${jar_present}" = true ]; then
        present=true
    fi
    printf '{ "present": %s, "native": %s, "jar": %s }' \
        "${present}" "${binary_present}" "${jar_present}"
}

# Variant metadata
cat > "${DIST_DIR}/.dist-info.json" << EOF
{
  "version": "${VERSION}",
  "variant": "${VARIANT}",
  "platform": "${PLATFORM}",
  "backendProfile": "${KOMPILE_BACKEND_PROFILE:-none}",
  "sdkClassifier": "${SDK_CLASSIFIER}",
  "distributionClassifier": "${DISTRIBUTION_CLASSIFIER}",
  "buildDate": "$(date -Iseconds)",
  "components": {
    "cli": $(component_forms kompile kompile-cli.jar),
    "agent-cli": $(component_forms kompile-agent kompile-agent.jar),
    "model-cli": $(component_forms kompile-model kompile-model.jar),
    "server": $(component_forms kompile-server kompile-server.jar),
    "model-staging": $(component_forms kompile-model-staging kompile-model-staging.jar),
    "model-serving": $(component_forms kompile-model-serving kompile-model-serving.jar),
    "pipeline-serving": $(component_forms kompile-pipeline-serving kompile-pipeline-serving.jar),
    "chat": $(component_forms kompile-chat kompile-chat.jar),
    "crawl-manager": $(component_forms kompile-crawl-manager kompile-crawl-manager.jar),
    "scripting-worker": $(component_forms kompile-scripting-worker kompile-scripting-worker.jar),
    "bundled-runtime": { "present": $([ -d "${DIST_DIR}/runtime" ] && echo true || echo false) },
    "sdx-sdk": {
      "present": $([ -d "${DIST_DIR}/sdx-sdk" ] && echo true || echo false),
      "runtimePackages": ${SDX_RUNTIME_COUNT},
      "platformJars": ${SDX_JAR_COUNT}
    }
  },
  "backend": "${ND4J_BACKEND:-none}"
}
EOF

# Record every shipped file inside the archive. Consumers can validate an
# extracted distribution without relying on an external release manifest.
checksum_value() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}
(
    cd "${DIST_DIR}"
    find . \( -type f -o -type l \) ! -name manifest.sha256 -print | LC_ALL=C sort | while IFS= read -r file; do
        printf '%s  %s\n' "$(checksum_value "${file}")" "${file#./}"
    done
) > "${DIST_DIR}/manifest.sha256"

echo ""
echo "  Distribution layout:"
find "${DIST_DIR}" -type f | sort | while read -r f; do
    echo "    ${f#${DIST_DIR}/}"
done

# Create both release formats. Distribution modules attach both under the same
# classifier; direct installers select tar.gz on Unix and ZIP on Windows.
echo ""
ZIP_ARCHIVE="${OUTPUT_DIR}/${DIST_NAME}.zip"
TAR_ARCHIVE="${OUTPUT_DIR}/${DIST_NAME}.tar.gz"
if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN=python3
elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN=python
else
    echo "  ERROR: Python 3 is required to create the distribution ZIP" >&2
    exit 1
fi
# Python is a native process on the Windows worker. Pass native paths explicitly;
# do not rely on MSYS argument conversion for the archive operation.
PYTHON_OUTPUT_DIR="$(kompile_path_to_native "${OUTPUT_DIR}")"
PYTHON_ZIP_ARCHIVE="$(kompile_path_to_native "${ZIP_ARCHIVE}")"
"${PYTHON_BIN}" - "${PYTHON_OUTPUT_DIR}" "${DIST_NAME}" "${PYTHON_ZIP_ARCHIVE}" <<'PY'
import os
import sys
import zipfile
from pathlib import Path

root, name, output = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
source = root / name
with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
    for current, directories, files in os.walk(source):
        directories.sort()
        files.sort()
        relative = Path(current).relative_to(root)
        if not directories and not files:
            archive.writestr(relative.as_posix().rstrip("/") + "/", b"")
        for file_name in files:
            path = Path(current) / file_name
            archive.write(path, path.relative_to(root).as_posix())
PY
(
    # Keep tar operands relative to the output directory.  On Windows/MSYS,
    # passing a C:\\... archive path directly makes tar treat the drive colon
    # as a remote-host separator ("Cannot connect to C").
    cd "${OUTPUT_DIR}"
    tar -czf "$(basename "${TAR_ARCHIVE}")" "${DIST_NAME}/"
)
printf '%s  %s\n' "$(checksum_value "${ZIP_ARCHIVE}")" "$(basename "${ZIP_ARCHIVE}")" > "${ZIP_ARCHIVE}.sha256"
printf '%s  %s\n' "$(checksum_value "${TAR_ARCHIVE}")" "$(basename "${TAR_ARCHIVE}")" > "${TAR_ARCHIVE}.sha256"

if [ "${INSTALL_MAVEN}" = true ]; then
    install_distribution_artifact() {
        local artifact_file="$1"
        local artifact_type="$2"
        echo "  Installing classified distribution ${artifact_type} in ${MAVEN_REPOSITORY_SHELL}"
        local -a install_args=(
            --batch-mode
            --no-transfer-progress
            "-Dmaven.repo.local=${MAVEN_REPOSITORY}"
            org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file
            "-Dfile=$(kompile_path_to_native "${artifact_file}")"
            -DgroupId=ai.kompile
            -DartifactId=kompile-dist
            "-Dversion=${VERSION}"
            "-Dpackaging=${artifact_type}"
            "-Dclassifier=${DISTRIBUTION_CLASSIFIER}"
            -DpomFile=kompile-dist/pom.xml
            -DgeneratePom=false
        )
        "${MVN}" "${MAVEN_BUILD_ARGS[@]}" "${install_args[@]}"
    }
    install_distribution_artifact "${ZIP_ARCHIVE}" zip
    install_distribution_artifact "${TAR_ARCHIVE}" tar.gz
fi

echo "  ZIP:      ${ZIP_ARCHIVE}"
echo "  ZIP size: $(du -h "${ZIP_ARCHIVE}" | cut -f1)"
echo "  SHA256:   $(cat "${ZIP_ARCHIVE}.sha256")"
echo "  Tarball:  ${TAR_ARCHIVE}"
echo ""
echo "════════════════════════════════════════════════════════════════"
echo " Done! Distribution: ${ZIP_ARCHIVE}"
echo "════════════════════════════════════════════════════════════════"
