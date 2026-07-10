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

# Backend flag must reach EVERY maven invocation (native + exec-jar steps too,
# not just the reactor install) — the poms select the ND4J artifact via
# ${nd4j.backend}; -Dkompile.cuda alone activates nothing at the Maven level.
BACKEND_FLAG=""
if [ -n "${ND4J_BACKEND}" ]; then
    BACKEND_FLAG="-Dnd4j.backend=${ND4J_BACKEND}"
fi

# Mixed-vintage guard: kompile bundles dl4j straight from ~/.m2, which accumulates partial `-pl`
# installs across sessions (api/presets/natives from different builds). Mixed vintages produce
# native-contract crashes that masquerade as dl4j bugs (2026-07-05 CUDA-700 misdiagnosis: Jun-22
# cuda preset bundled under Jul-5 natives). Warn when the SNAPSHOT set spans >6h of build times.
if [ -n "${ND4J_BACKEND}" ]; then
    DL4J_M2="${HOME}/.m2/repository/org/eclipse/deeplearning4j"
    if [ -d "${DL4J_M2}" ]; then
        VINTAGE_SPAN_H=$(find "${DL4J_M2}" -maxdepth 3 -name '*-1.0.0-SNAPSHOT*.jar' -printf '%T@\n' 2>/dev/null | sort -n | awk 'NR==1{f=$1} {l=$1} END{if (NR>1) printf "%d", (l-f)/3600}' || true)
        if [ -n "${VINTAGE_SPAN_H:-}" ] && [ "${VINTAGE_SPAN_H}" -gt 6 ]; then
            echo "⚠️  WARNING: dl4j SNAPSHOT jars in ~/.m2 span ${VINTAGE_SPAN_H}h of build vintages."
            echo "    Mixed api/preset/native vintages cause illegal-memory-access crashes at runtime."
            echo "    Rebuild the nd4j reactor at HEAD in ONE pass before trusting this distribution."
        fi
    fi
fi

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
        BUILD_CMD="${BUILD_CMD} -Dkompile.uber"
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
    APP_EXEC_JAR=$(ls kompile-app/kompile-app-parent/kompile-app-main/target/*-exec.jar 2>/dev/null | head -1)
    STAGING_EXEC_JAR=$(ls kompile-app/kompile-models/kompile-model-staging/target/*-exec.jar 2>/dev/null | head -1)

    if [ -z "${APP_EXEC_JAR}" ] || [ -z "${STAGING_EXEC_JAR}" ]; then
        echo ""
        echo "──── Step 1b: Building exec JARs ─────────────────────────────────"
        echo ""
    fi

    if [ -z "${APP_EXEC_JAR}" ]; then
        echo "  kompile-app-main: building exec JAR..."
        (
            cd kompile-app/kompile-app-parent/kompile-app-main
            ${MVN} package -DskipTests -Dkompile.uber ${BACKEND_FLAG} ${CUDA_FLAG} ${EXTRA_MVN_FLAGS} \
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
        CLI_TARGET="kompile-cli/kompile-cli-main/target/kompile-cli-main"
        if [ -f "${CLI_TARGET}" ] && [ "${SKIP_JAVA_BUILD}" = true ]; then
            echo "  kompile-cli: using existing binary"
        else
            echo "  kompile-cli: building native image..."
            (
                cd kompile-cli/kompile-cli-main
                ${MVN} package ${NATIVE_BUILD_FLAG} -DskipTests ${BACKEND_FLAG} ${CUDA_FLAG} ${EXTRA_MVN_FLAGS} \
                    2>&1 | tee /tmp/kompile-cli-native.log
            ) &
            PIDS+=($!)
        fi
    fi

    # App main native
    if [ "${APP_NATIVE}" = true ]; then
        echo "  kompile-app-main: building native image..."
        (
            cd kompile-app/kompile-app-parent/kompile-app-main
            # -Dkompile.uber also produces the exec jar alongside the native build
                ${MVN} package ${NATIVE_BUILD_FLAG} -Dkompile.uber -DskipTests ${BACKEND_FLAG} ${CUDA_FLAG} ${EXTRA_MVN_FLAGS} \
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
            cd kompile-app/kompile-models/kompile-model-staging
            ${MVN} package ${NATIVE_BUILD_FLAG} -DskipTests ${BACKEND_FLAG} ${CUDA_FLAG} ${EXTRA_MVN_FLAGS} \
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

    # Try compress=zip-6 first, fall back to compress=2 for older jlink.
    local compress_opt="--compress=zip-6"
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

if [ "${VARIANT}" != "cli-only" ]; then
    echo ""
    echo "──── Step 2b: Bundling jlink runtime ──────────────────────────────"
    echo ""
    RUNTIME_DEST="${OUTPUT_DIR}/.runtime-stage"
    rm -rf "${RUNTIME_DEST}"
    build_runtime "${RUNTIME_DEST}"
fi

# ── Binary portability normalizer ────────────────────────────────────────────
# Patches ELF interpreter and RUNPATH so native binaries copied into the dist
# run on any glibc x86_64 Linux (e.g. Amazon Linux 2023 in the spin image).
# Only the DIST copy is patched — target/ is left untouched.
# Silently skips non-ELF files and non-x86_64 arches; emits a SKIP warning if
# patchelf is not available.
_PATCHELF=""
for _pe_candidate in \
        "$(command -v patchelf 2>/dev/null || true)" \
        "/home/linuxbrew/.linuxbrew/bin/patchelf" \
        "${HOME}/.local/bin/patchelf"; do
    if [ -x "${_pe_candidate}" ]; then
        _PATCHELF="${_pe_candidate}"
        break
    fi
done

normalize_elf_portability() {
    local bin="$1"
    [ -f "${bin}" ] || return 0

    # Only patch ELF files.
    if ! file "${bin}" 2>/dev/null | grep -q 'ELF'; then
        return 0
    fi

    if [ -z "${_PATCHELF}" ]; then
        echo "  SKIP: patchelf not found — ${bin} interpreter/RPATH not normalized (binary may fail outside this host)"
        return 0
    fi

    local changed=0

    # ── Interpreter ──────────────────────────────────────────────────────────
    if [ "${OS}" = "linux" ] && [ "${ARCH}" = "x86_64" ]; then
        local CANONICAL_INTERP="/lib64/ld-linux-x86-64.so.2"
        local current_interp
        current_interp=$(readelf -l "${bin}" 2>/dev/null | grep 'interpreter:' | sed 's/.*interpreter: \(.*\)\]/\1/' || true)
        if [ -n "${current_interp}" ] && [ "${current_interp}" != "${CANONICAL_INTERP}" ]; then
            echo "  patchelf: ${bin}: interpreter ${current_interp} → ${CANONICAL_INTERP}"
            "${_PATCHELF}" --set-interpreter "${CANONICAL_INTERP}" "${bin}"
            changed=1
        fi
    fi

    # ── RUNPATH / RPATH ──────────────────────────────────────────────────────
    local current_rpath
    current_rpath=$(readelf -d "${bin}" 2>/dev/null | grep -E 'RPATH|RUNPATH' | sed 's/.*\[\(.*\)\]/\1/' || true)
    if echo "${current_rpath}" | grep -qi 'linuxbrew\|homebrew\|/home/'; then
        echo "  patchelf: ${bin}: stripping non-portable RPATH (${current_rpath}) → \$ORIGIN/../lib"
        "${_PATCHELF}" --remove-rpath "${bin}"
        "${_PATCHELF}" --set-rpath '$ORIGIN/../lib' "${bin}"
        changed=1
    fi

    [ "${changed}" -eq 0 ] && echo "  portability OK: ${bin} (interpreter/RPATH already portable)"
    return 0
}

# ── Step 3: Package distribution ─────────────────────────────────────────────

echo ""
echo "──── Step 3: Packaging distribution ───────────────────────────────"
echo ""

DIST_NAME="kompile-dist-${VERSION}-${VARIANT}-${PLATFORM}"
DIST_DIR="${OUTPUT_DIR}/${DIST_NAME}"

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"/{bin,lib,config,data}

# Copy jlink runtime into the dist (if it was staged above).
if [ "${VARIANT}" != "cli-only" ] && [ -d "${RUNTIME_DEST:-}" ] && [ -x "${RUNTIME_DEST}/bin/java" ]; then
    cp -a "${RUNTIME_DEST}" "${DIST_DIR}/runtime"
    echo "  runtime/ (bundled JDK — $(du -sh "${DIST_DIR}/runtime" | cut -f1))"
fi

# Copy CLI binary (canonical name: bin/kompile; back-compat symlink: bin/kompile-cli)
CLI_BIN="kompile-cli/kompile-cli-main/target/kompile-cli-main"
if [ -f "${CLI_BIN}" ]; then
    cp "${CLI_BIN}" "${DIST_DIR}/bin/kompile"
    chmod +x "${DIST_DIR}/bin/kompile"
    normalize_elf_portability "${DIST_DIR}/bin/kompile"
    # Back-compat symlink
    ln -sf kompile "${DIST_DIR}/bin/kompile-cli"
    echo "  bin/kompile ($(du -h "${CLI_BIN}" | cut -f1)) + bin/kompile-cli symlink"
fi

# Copy CLI shaded jar into lib/ when present (JBang fallback)
CLI_SHADED_JAR="kompile-cli/kompile-cli-main/target/kompile-cli-main-${VERSION}-shaded.jar"
if [ -f "${CLI_SHADED_JAR}" ]; then
    cp "${CLI_SHADED_JAR}" "${DIST_DIR}/lib/kompile-cli.jar"
    echo "  lib/kompile-cli.jar ($(du -h "${CLI_SHADED_JAR}" | cut -f1))"
else
    echo "  WARN: ${CLI_SHADED_JAR} not found — lib/kompile-cli.jar will be absent (build with shade plugin to include)"
fi

# Copy app-main (native binary: bin/kompile-server; back-compat symlink: bin/kompile-app-main)
if [ "${APP_NATIVE}" = true ]; then
    APP_BIN="kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app"
    APP_EXEC_JAR_PATH="kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app-main-${VERSION}-exec.jar"
    if [ -f "${APP_BIN}" ]; then
        cp "${APP_BIN}" "${DIST_DIR}/bin/kompile-server"
        chmod +x "${DIST_DIR}/bin/kompile-server"
        normalize_elf_portability "${DIST_DIR}/bin/kompile-server"
        # Back-compat symlink
        ln -sf kompile-server "${DIST_DIR}/bin/kompile-app-main"
        echo "  bin/kompile-server ($(du -h "${APP_BIN}" | cut -f1)) + bin/kompile-app-main symlink"
        # GraalVM-emitted JDK shim libraries (libawt.so, libjava.so, libjvm.so, ...)
        # must ship next to the binary; kompile-server.sh puts bin/ on LD_LIBRARY_PATH.
        SHIM_COUNT=0
        for shim in kompile-app/kompile-app-parent/kompile-app-main/target/lib*.so; do
            [ -f "${shim}" ] || continue
            cp -a "${shim}" "${DIST_DIR}/bin/"
            SHIM_COUNT=$((SHIM_COUNT + 1))
        done
        if [ "${SHIM_COUNT}" -gt 0 ]; then
            echo "  bin/ (+${SHIM_COUNT} GraalVM JDK shim libraries)"
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
fi

# Copy model staging (native binary or JAR fallback)
if [ "${STAGING_NATIVE}" = true ]; then
    if [ -f "kompile-app/kompile-models/kompile-model-staging/target/kompile-model-staging" ]; then
        cp "kompile-app/kompile-models/kompile-model-staging/target/kompile-model-staging" "${DIST_DIR}/bin/kompile-model-staging"
        chmod +x "${DIST_DIR}/bin/kompile-model-staging"
        normalize_elf_portability "${DIST_DIR}/bin/kompile-model-staging"
        echo "  bin/kompile-model-staging ($(du -h kompile-app/kompile-models/kompile-model-staging/target/kompile-model-staging | cut -f1))"
    elif ls kompile-app/kompile-models/kompile-model-staging/target/*-exec.jar 1>/dev/null 2>&1; then
        JAR=$(ls kompile-app/kompile-models/kompile-model-staging/target/*-exec.jar | head -1)
        cp "${JAR}" "${DIST_DIR}/lib/kompile-model-staging.jar"
        echo "  lib/kompile-model-staging.jar ($(du -h "${JAR}" | cut -f1)) [fallback: JAR]"
    fi
fi

# Copy JBang catalog and quick-start guide into the dist root (every variant)
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

# ── Optional artifacts that dist.xml also lists (copy-if-present, logged when skipped) ──
# These are only produced when their respective sub-projects are built; all copies are
# safe to skip in cli-only or hosted builds that don't include those modules.

# kompile-sdk-serving shaded jar → lib/kompile-sdk-serving.jar
SDK_SERVING_JAR=$(find kompile-app/kompile-middleware/kompile-sdk-serving/target \
    -maxdepth 1 -name '*-shaded.jar' 2>/dev/null | head -1)
if [ -n "${SDK_SERVING_JAR}" ]; then
    cp "${SDK_SERVING_JAR}" "${DIST_DIR}/lib/kompile-sdk-serving.jar"
    echo "  lib/kompile-sdk-serving.jar ($(du -h "${SDK_SERVING_JAR}" | cut -f1))"
else
    echo "  SKIP: kompile-sdk-serving shaded jar not found (build kompile-middleware to include)"
fi

# GraalVM shared library + headers from kompile-pipelines-framework-runtime → lib/
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

# CMake-built C wrapper → lib/libkompile_c_library.*
for clib_file in libkompile_c_library.so libkompile_c_library.dylib libkompile_c_library.dll; do
    if [ -f "kompile-c-library/${clib_file}" ]; then
        cp "kompile-c-library/${clib_file}" "${DIST_DIR}/lib/${clib_file}"
        echo "  lib/${clib_file} ($(du -h "kompile-c-library/${clib_file}" | cut -f1))"
    else
        echo "  SKIP: ${clib_file} not found (build kompile-c-library with CMake to include)"
    fi
done

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

# Copy extra CLI binaries if they exist (agent, model, component, app-cli)
for extra in kompile-cli/kompile-agent-cli/target/kompile-agent \
             kompile-cli/kompile-app-cli/target/kompile-app-cli \
             kompile-cli/kompile-model-cli/target/kompile-model \
             kompile-cli/kompile-component-cli/target/kompile-component; do
    if [ -f "${extra}" ]; then
        BNAME=$(basename "${extra}")
        cp "${extra}" "${DIST_DIR}/bin/${BNAME}"
        chmod +x "${DIST_DIR}/bin/${BNAME}"
        normalize_elf_portability "${DIST_DIR}/bin/${BNAME}"
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
# First check if the unpack-native-libs execution already extracted them
# (app-main lives under kompile-app-parent since the subprocess-module split)
APP_NATIVE_LIBS="kompile-app/kompile-app-parent/kompile-app-main/target/native-libs"
if [ -d "${APP_NATIVE_LIBS}" ]; then
    SO_COUNT=$(find "${APP_NATIVE_LIBS}" \( -name '*.so' -o -name '*.so.*' \) 2>/dev/null | wc -l)
    if [ "${SO_COUNT}" -gt 0 ]; then
        echo ""
        echo "  Copying ${SO_COUNT} native libraries to lib/..."
        find "${APP_NATIVE_LIBS}" \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dll' \) \
            -exec cp -a {} "${DIST_DIR}/lib/" \; 2>/dev/null || true
        chmod +x "${DIST_DIR}/lib/"*.so* 2>/dev/null || true
        # JavaCPP fabricates alias sonames at cache-extraction time; jars carry no
        # symlinks, so recreate the known ones (libjniopenblas_nolapack needs it).
        if [ -f "${DIST_DIR}/lib/libopenblas.so.0" ] && [ ! -e "${DIST_DIR}/lib/libopenblas_nolapack.so.0" ]; then
            ln -s libopenblas.so.0 "${DIST_DIR}/lib/libopenblas_nolapack.so.0"
        fi
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
            DEST="${DIST_DIR}/lib/${SONAME}"
            # Skip symlinks with relative targets — these are in-jar alias stubs
            # (e.g. libjniopenblas_full.so -> ../libjniopenblas_full.so) whose
            # target won't exist relative to lib/.  The real file is iterated
            # separately from another cache path and will be copied below.
            if [ -L "${sofile}" ]; then
                TARGET=$(readlink "${sofile}")
                case "${TARGET}" in
                    /*)
                        # Absolute symlink: dereference so lib/ gets a real file.
                        rm -f "${DEST}"
                        cp -L "${sofile}" "${DEST}"
                        ;;
                    *)
                        echo "  SKIP: ${SONAME} (relative symlink alias → ${TARGET})"
                        ;;
                esac
                continue
            fi
            # Real file: clear any dangling symlink at dest before writing.
            if [ -L "${DEST}" ]; then
                rm -f "${DEST}"
            fi
            if [ ! -f "${DEST}" ]; then
                cp "${sofile}" "${DEST}"
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
    "cli": $([ -f "${DIST_DIR}/bin/kompile" ] && echo true || echo false),
    "server": $([ -f "${DIST_DIR}/bin/kompile-server" ] || [ -f "${DIST_DIR}/lib/kompile-server.jar" ] && echo true || echo false),
    "model-staging": $([ -f "${DIST_DIR}/bin/kompile-model-staging" ] || [ -f "${DIST_DIR}/lib/kompile-model-staging.jar" ] && echo true || echo false),
    "bundled-runtime": $([ -d "${DIST_DIR}/runtime" ] && echo true || echo false)
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
