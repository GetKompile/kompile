#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Kompile Distribution Builder
#
# Builds platform-specific distribution archives containing native binaries.
#
# Usage:
#   ./build-dist.sh <variant> [options]
#
# Variants:
#   cli-only      — Just the kompile-cli binary (smallest, fastest)
#   hosted        — CLI + app-main + staging (for API-key/hosted LLM users)
#   cpu-intel     — CLI + app-main + staging with nd4j-native x86_64
#   cpu-arm       — CLI + app-main + staging with nd4j-native aarch64
#   cuda          — CLI + app-main + staging with nd4j-cuda
#   amd-zluda     — CLI + app-main + staging with ZLUDA backend
#
# Options:
#   --skip-java-build    Skip the Maven Java install (use existing target/)
#   --skip-native        Skip native image compilation (use existing binaries)
#   --jars-only          Use exec JARs instead of native images (implies --skip-native)
#   --parallel N         Number of parallel native-image builds (default: 1)
#   --output-dir DIR     Where to write the final .tar.gz (default: ./dist/)
#   --version VER        Version string (default: from pom.xml)
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"

# ── Configuration ────────────────────────────────────────────────────────────

GRAALVM_HOME="${GRAALVM_HOME:-${HOME}/.sdkman/candidates/java/21.0.10-graal}"
MVN="${MVN:-/home/agibsonccc/dev-apps/mvn/bin/mvn}"
JAVA_HOME="${GRAALVM_HOME}"
export JAVA_HOME

VARIANT="${1:-}"
SKIP_JAVA_BUILD=false
SKIP_NATIVE=false
JARS_ONLY=false
PARALLEL=1
OUTPUT_DIR="${SCRIPT_DIR}/dist"
VERSION=""

shift || true
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-java-build)  SKIP_JAVA_BUILD=true; shift ;;
        --skip-native)      SKIP_NATIVE=true; shift ;;
        --jars-only)        JARS_ONLY=true; SKIP_NATIVE=true; shift ;;
        --parallel)         PARALLEL="$2"; shift 2 ;;
        --output-dir)       OUTPUT_DIR="$2"; shift 2 ;;
        --version)          VERSION="$2"; shift 2 ;;
        *)                  echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

if [ -z "${VARIANT}" ]; then
    echo "Usage: $0 <variant> [options]"
    echo ""
    echo "Variants: cli-only, hosted, cpu-intel, cpu-arm, cuda, amd-zluda"
    exit 1
fi

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
PLATFORM="${OS}-${ARCH}"

echo "════════════════════════════════════════════════════════════════"
echo " Kompile Distribution Builder"
echo "════════════════════════════════════════════════════════════════"
echo "  Variant:   ${VARIANT}"
echo "  Platform:  ${PLATFORM}"
echo "  Version:   ${VERSION}"
echo "  GraalVM:   ${GRAALVM_HOME}"
echo "  Output:    ${OUTPUT_DIR}"
echo "  Parallel:  ${PARALLEL}"
echo ""

# ── Variant configuration ────────────────────────────────────────────────────

# What to build for each variant
CLI_NATIVE=true            # Always build CLI
APP_NATIVE=false           # kompile-app-main native
STAGING_NATIVE=false       # kompile-model-staging native
ND4J_BACKEND=""            # empty = no local models
CUDA_FLAG=""               # -Dkompile.cuda=true for CUDA variants
EXTRA_MVN_FLAGS=""

case "${VARIANT}" in
    cli-only)
        APP_NATIVE=false
        STAGING_NATIVE=false
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
        EXTRA_MVN_FLAGS="-Dlibnd4j.extension=avx2"
        ;;
    cpu-arm)
        APP_NATIVE=true
        STAGING_NATIVE=true
        ND4J_BACKEND="nd4j-native"
        ;;
    cuda)
        APP_NATIVE=true
        STAGING_NATIVE=true
        ND4J_BACKEND="nd4j-cuda-12.9"
        CUDA_FLAG="-Dkompile.cuda=true"
        ;;
    amd-zluda)
        APP_NATIVE=true
        STAGING_NATIVE=true
        ND4J_BACKEND="nd4j-cuda-12.9"  # ZLUDA is CUDA-compatible
        CUDA_FLAG="-Dkompile.cuda=true"
        EXTRA_MVN_FLAGS="-Dkompile.zluda=true"
        ;;
    *)
        echo "Unknown variant: ${VARIANT}" >&2
        echo "Valid: cli-only, hosted, cpu-intel, cpu-arm, cuda, amd-zluda" >&2
        exit 1
        ;;
esac

# ── Step 1: Java build ───────────────────────────────────────────────────────

if [ "${SKIP_JAVA_BUILD}" = false ]; then
    echo "──── Step 1: Building Java modules ────────────────────────────────"
    echo ""

    BUILD_CMD="${MVN} clean install -DskipTests"
    if [ -n "${ND4J_BACKEND}" ]; then
        BUILD_CMD="${BUILD_CMD} -Dnd4j.backend=${ND4J_BACKEND}"
    fi
    if [ -n "${CUDA_FLAG}" ]; then
        BUILD_CMD="${BUILD_CMD} ${CUDA_FLAG}"
    fi
    if [ -n "${EXTRA_MVN_FLAGS}" ]; then
        BUILD_CMD="${BUILD_CMD} ${EXTRA_MVN_FLAGS}"
    fi
    # When building JARs (not native), produce exec JARs for app-main
    if [ "${JARS_ONLY}" = true ] && [ "${APP_NATIVE}" = true ]; then
        BUILD_CMD="${BUILD_CMD} -Dkompile.dist.jar=true"
    fi

    echo "  Command: ${BUILD_CMD}"
    echo ""
    eval "${BUILD_CMD}" 2>&1 | tee /tmp/kompile-java-build.log
    echo ""
    echo "  ✓ Java build complete"
else
    echo "──── Step 1: Skipped (--skip-java-build) ──────────────────────────"
fi

# ── Step 1b: Build exec JARs if needed (skip-java-build but jars-only) ──────

if [ "${SKIP_JAVA_BUILD}" = true ] && [ "${JARS_ONLY}" = true ] && [ "${APP_NATIVE}" = true ]; then
    # Check if exec JARs already exist
    APP_EXEC_JAR=$(ls kompile-app/kompile-app-main/target/*-exec.jar 2>/dev/null | head -1)
    STAGING_EXEC_JAR=$(ls kompile-app/kompile-model-staging/target/*-exec.jar 2>/dev/null | head -1)

    if [ -z "${APP_EXEC_JAR}" ] || [ -z "${STAGING_EXEC_JAR}" ]; then
        echo ""
        echo "──── Step 1b: Building exec JARs ─────────────────────────────────"
        echo ""
    fi

    if [ -z "${APP_EXEC_JAR}" ]; then
        echo "  kompile-app-main: building exec JAR..."
        (
            cd kompile-app/kompile-app-main
            ${MVN} package -DskipTests -Dkompile.dist.jar=true ${CUDA_FLAG} ${EXTRA_MVN_FLAGS} \
                2>&1 | tee /tmp/kompile-app-main-jar.log
        )
        echo "  ✓ kompile-app-main exec JAR built"
    fi

    if [ "${STAGING_NATIVE}" = true ] && [ -z "${STAGING_EXEC_JAR}" ]; then
        echo "  kompile-model-staging: exec JAR already built by default"
    fi
fi

# ── Step 2: Native image builds ──────────────────────────────────────────────

if [ "${SKIP_NATIVE}" = false ]; then
    echo ""
    echo "──── Step 2: Building native images ───────────────────────────────"
    echo ""

    NATIVE_BUILD_FLAG="-Dkompile.dist=true"
    PIDS=()

    # CLI native (always)
    if [ "${CLI_NATIVE}" = true ]; then
        CLI_TARGET="kompile-cli/target/kompile-cli"
        if [ -f "${CLI_TARGET}" ] && [ "${SKIP_JAVA_BUILD}" = true ]; then
            echo "  kompile-cli: using existing binary"
        else
            echo "  kompile-cli: building native image..."
            (
                cd kompile-cli
                ${MVN} package ${NATIVE_BUILD_FLAG} -DskipTests ${CUDA_FLAG} ${EXTRA_MVN_FLAGS} \
                    2>&1 | tee /tmp/kompile-cli-native.log
            ) &
            PIDS+=($!)
        fi
    fi

    # App main native
    if [ "${APP_NATIVE}" = true ]; then
        echo "  kompile-app-main: building native image..."
        (
            cd kompile-app/kompile-app-main
            ${MVN} package ${NATIVE_BUILD_FLAG} -DskipTests ${CUDA_FLAG} ${EXTRA_MVN_FLAGS} \
                2>&1 | tee /tmp/kompile-app-main-native.log
        ) &
        PIDS+=($!)

        # Throttle if not parallel
        if [ "${PARALLEL}" -le 1 ] && [ ${#PIDS[@]} -ge 1 ]; then
            wait "${PIDS[-1]}"
        fi
    fi

    # Model staging native
    if [ "${STAGING_NATIVE}" = true ]; then
        echo "  kompile-model-staging: building native image..."
        (
            cd kompile-app/kompile-model-staging
            ${MVN} package ${NATIVE_BUILD_FLAG} -DskipTests ${CUDA_FLAG} ${EXTRA_MVN_FLAGS} \
                2>&1 | tee /tmp/kompile-model-staging-native.log
        ) &
        PIDS+=($!)
    fi

    # Wait for all native builds
    echo ""
    echo "  Waiting for ${#PIDS[@]} native build(s)..."
    FAILED=0
    for pid in "${PIDS[@]}"; do
        if ! wait "${pid}"; then
            FAILED=$((FAILED + 1))
        fi
    done

    if [ "${FAILED}" -gt 0 ]; then
        echo "  ✗ ${FAILED} native build(s) failed. Check /tmp/kompile-*-native.log"
        exit 1
    fi
    echo "  ✓ All native images built"
else
    echo ""
    echo "──── Step 2: Skipped (--skip-native) ──────────────────────────────"
fi

# ── Step 3: Package distribution ─────────────────────────────────────────────

echo ""
echo "──── Step 3: Packaging distribution ───────────────────────────────"
echo ""

DIST_NAME="kompile-dist-${VERSION}-${VARIANT}-${PLATFORM}"
DIST_DIR="${OUTPUT_DIR}/${DIST_NAME}"

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"/{bin,lib,config,data}

# Copy CLI binary
if [ -f "kompile-cli/target/kompile-cli" ]; then
    cp kompile-cli/target/kompile-cli "${DIST_DIR}/bin/"
    chmod +x "${DIST_DIR}/bin/kompile-cli"
    echo "  bin/kompile-cli ($(du -h kompile-cli/target/kompile-cli | cut -f1))"
fi

# Copy app-main (native binary or JAR fallback)
if [ "${APP_NATIVE}" = true ]; then
    if [ -f "kompile-app/kompile-app-main/target/kompile-app" ]; then
        cp "kompile-app/kompile-app-main/target/kompile-app" "${DIST_DIR}/bin/kompile-app-main"
        chmod +x "${DIST_DIR}/bin/kompile-app-main"
        echo "  bin/kompile-app-main ($(du -h kompile-app/kompile-app-main/target/kompile-app | cut -f1))"
    elif ls kompile-app/kompile-app-main/target/*-exec.jar 1>/dev/null 2>&1; then
        JAR=$(ls kompile-app/kompile-app-main/target/*-exec.jar | head -1)
        cp "${JAR}" "${DIST_DIR}/lib/kompile-app-main.jar"
        echo "  lib/kompile-app-main.jar ($(du -h "${JAR}" | cut -f1)) [fallback: JAR]"
    fi
fi

# Copy model staging (native binary or JAR fallback)
if [ "${STAGING_NATIVE}" = true ]; then
    if [ -f "kompile-app/kompile-model-staging/target/kompile-model-staging" ]; then
        cp "kompile-app/kompile-model-staging/target/kompile-model-staging" "${DIST_DIR}/bin/kompile-model-staging"
        chmod +x "${DIST_DIR}/bin/kompile-model-staging"
        echo "  bin/kompile-model-staging ($(du -h kompile-app/kompile-model-staging/target/kompile-model-staging | cut -f1))"
    elif ls kompile-app/kompile-model-staging/target/*-exec.jar 1>/dev/null 2>&1; then
        JAR=$(ls kompile-app/kompile-model-staging/target/*-exec.jar | head -1)
        cp "${JAR}" "${DIST_DIR}/lib/kompile-model-staging.jar"
        echo "  lib/kompile-model-staging.jar ($(du -h "${JAR}" | cut -f1)) [fallback: JAR]"
    fi
fi

# Copy extra CLI binaries if they exist (agent, model, component, app-cli)
for extra in kompile-agent-cli/target/kompile-agent \
             kompile-app-cli/target/kompile-app-cli \
             kompile-model-cli/target/kompile-model \
             kompile-component-cli/target/kompile-component; do
    if [ -f "${extra}" ]; then
        BNAME=$(basename "${extra}")
        cp "${extra}" "${DIST_DIR}/bin/${BNAME}"
        chmod +x "${DIST_DIR}/bin/${BNAME}"
        echo "  bin/${BNAME} ($(du -h "${extra}" | cut -f1))"
    fi
done

# Copy build scripts for platform rebuilds from installed dist
if [ -d "build-scripts" ]; then
    mkdir -p "${DIST_DIR}/build-scripts"
    cp build-scripts/*.sh "${DIST_DIR}/build-scripts/"
    chmod +x "${DIST_DIR}/build-scripts/"*.sh
    SCRIPT_COUNT=$(ls "${DIST_DIR}/build-scripts/"*.sh 2>/dev/null | wc -l)
    echo "  build-scripts/ (${SCRIPT_COUNT} scripts)"
fi

# Copy native .so libraries into lib/ for NativeLibraryResolver
# First check if native-dist assembly already extracted them
if [ -d "kompile-app/kompile-app-main/target/native-libs" ]; then
    SO_COUNT=$(find kompile-app/kompile-app-main/target/native-libs -maxdepth 1 -name '*.so' -o -name '*.so.*' 2>/dev/null | wc -l)
    if [ "${SO_COUNT}" -gt 0 ]; then
        echo ""
        echo "  Copying ${SO_COUNT} native libraries to lib/..."
        cp -a kompile-app/kompile-app-main/target/native-libs/*.so* "${DIST_DIR}/lib/" 2>/dev/null || true
        chmod +x "${DIST_DIR}/lib/"*.so* 2>/dev/null || true
        echo "  lib/ ($(du -sh "${DIST_DIR}/lib/" | cut -f1) total native libs)"
    fi
else
    # Fallback: extract from JavaCPP cache if available
    JAVACPP_CACHE="${HOME}/.javacpp/cache"
    if [ -d "${JAVACPP_CACHE}" ]; then
        echo ""
        echo "  Extracting native libraries from JavaCPP cache..."
        find "${JAVACPP_CACHE}" -path "*/${PLATFORM}/*" \( -name '*.so' -o -name '*.so.*' \) | while read -r sofile; do
            SONAME=$(basename "${sofile}")
            if [ ! -f "${DIST_DIR}/lib/${SONAME}" ]; then
                cp -a "${sofile}" "${DIST_DIR}/lib/${SONAME}"
            fi
        done
        chmod +x "${DIST_DIR}/lib/"*.so* 2>/dev/null || true
        SO_COUNT=$(ls "${DIST_DIR}/lib/"*.so* 2>/dev/null | wc -l)
        echo "  lib/ (${SO_COUNT} libraries from JavaCPP cache)"
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

# Variant metadata
cat > "${DIST_DIR}/.dist-info.json" << EOF
{
  "version": "${VERSION}",
  "variant": "${VARIANT}",
  "platform": "${PLATFORM}",
  "buildDate": "$(date -Iseconds)",
  "components": {
    "cli": $([ -f "${DIST_DIR}/bin/kompile-cli" ] && echo true || echo false),
    "app-main": $([ -f "${DIST_DIR}/bin/kompile-app-main" ] || [ -f "${DIST_DIR}/lib/kompile-app-main.jar" ] && echo true || echo false),
    "model-staging": $([ -f "${DIST_DIR}/bin/kompile-model-staging" ] || [ -f "${DIST_DIR}/lib/kompile-model-staging.jar" ] && echo true || echo false)
  },
  "backend": "${ND4J_BACKEND:-none}"
}
EOF

echo ""
echo "  Distribution layout:"
find "${DIST_DIR}" -type f | sort | while read -r f; do
    echo "    ${f#${DIST_DIR}/}"
done

# Create archive
echo ""
ARCHIVE="${OUTPUT_DIR}/${DIST_NAME}.tar.gz"
tar -czf "${ARCHIVE}" -C "${OUTPUT_DIR}" "${DIST_NAME}/"
sha256sum "${ARCHIVE}" > "${ARCHIVE}.sha256"

echo "  Archive: ${ARCHIVE}"
echo "  Size:    $(du -h "${ARCHIVE}" | cut -f1)"
echo "  SHA256:  $(cat "${ARCHIVE}.sha256")"
echo ""
echo "════════════════════════════════════════════════════════════════"
echo " Done! Distribution: ${ARCHIVE}"
echo "════════════════════════════════════════════════════════════════"
